package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.mcp.session.eventlog.EventLogReader
import io.beyondwin.fixthis.mcp.session.eventlog.EventLogWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.UUID

private val eventLogJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * In-memory store for feedback sessions, with optional write-ahead event log support.
 *
 * When [eventLogWriterProvider] is non-null, every spec'd mutation appends a
 * [SessionEvent] BEFORE updating in-memory state. If the append throws
 * [io.beyondwin.fixthis.mcp.session.eventlog.EventLogException], memory remains
 * unchanged (fail-stop).
 *
 * When [eventLogReaderProvider] is non-null, the init block replays events from
 * the log to reconstruct session state on boot (A.4 simplification: events
 * override the state.json snapshot for items/screens/handoffBatches, while the
 * session shell — id, package, projectRoot, createdAt — comes from persistence).
 *
 * When both are null, the store behaves identically to the pre-A.4 baseline,
 * preserving backward compatibility for the ~482 existing tests.
 */
// LargeClass suppressed: split into smaller responsibilities once the event-log API stabilises — see #ALH-followup
@Suppress("LargeClass")
class FeedbackSessionStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val persistence: FeedbackSessionPersistence? = null,
    private val eventLogWriterProvider: ((sessionId: String) -> EventLogWriter)? = null,
    private val eventLogReaderProvider: ((sessionId: String) -> EventLogReader)? = null,
) {
    private val lock = Any()
    private val sessions = linkedMapOf<String, SessionDto>()
    private var currentSessionId: String? = null

    private val journal = SessionEventJournal(
        clock = clock,
        idGenerator = idGenerator,
        writerProvider = eventLogWriterProvider,
        readerProvider = eventLogReaderProvider,
    )
    private val replayEngine = SessionReplayEngine(journal, persistence)
    private val replaySkippedSessions = mutableMapOf<String, SkippedFeedbackSession>()

    init {
        persistence?.let { p ->
            p.list(includeClosed = true).sessions
                .sortedBy { it.updatedAtEpochMillis }
                .forEach { summary ->
                    runCatching { p.load(summary.sessionId) }
                        .getOrNull()
                        ?.let { session ->
                            sessions[session.sessionId] = session
                            if (session.status != SessionStatusDto.CLOSED) currentSessionId = session.sessionId
                        }
                }
        }

        // Boot replay: if readerProvider is wired, replay events for every known session.
        // Simplification (A.4): for sessions that have events, reset items/screens/handoffBatches
        // to empty then replay all events. The session shell (id, packageName, projectRoot,
        // createdAt, status) is preserved from the persistence snapshot.
        if (eventLogReaderProvider != null) {
            val sessionIds = sessions.keys.toList()
            for (sid in sessionIds) {
                replaySessionEvents(sid)
            }
            // Re-derive currentSessionId from post-replay statuses
            currentSessionId = sessions.values
                .filter { it.status != SessionStatusDto.CLOSED }
                .maxByOrNull { it.updatedAtEpochMillis }
                ?.sessionId
        }
    }

    // ------------------------------------------------------------------
    // Public API — unchanged signatures
    // ------------------------------------------------------------------

    fun openSession(packageName: String, projectRoot: String): SessionDto = synchronized(lock) {
        val now = clock()
        val session = SessionDto(
            sessionId = idGenerator(),
            packageName = packageName,
            projectRoot = projectRoot,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        save(session)
        sessions[session.sessionId] = session
        currentSessionId = session.sessionId
        session
    }

    fun currentSession(): SessionDto? = synchronized(lock) { currentSessionId?.let { getSessionLocked(it) } }

    fun getSession(sessionId: String): SessionDto = synchronized(lock) {
        getSessionLocked(sessionId)
    }

    fun replaceSessionForDomain(session: SessionDto): SessionDto = synchronized(lock) {
        val migrated = session.withMigratedItemSequenceCounter()
        save(migrated)
        sessions[migrated.sessionId] = migrated
        if (migrated.status != SessionStatusDto.CLOSED) {
            currentSessionId = migrated.sessionId
        } else if (currentSessionId == migrated.sessionId) {
            currentSessionId = null
        }
        migrated
    }

    fun addOrReplaceScreenForDomain(sessionId: String, screen: SnapshotDto): SessionDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val updated = session.copy(
            screens = session.screens.filterNot { it.screenId == screen.screenId } + screen,
            updatedAtEpochMillis = clock(),
        )
        commitSessionMutation(session, updated)
    }

    fun addOrReplaceAnnotationForDomain(sessionId: String, annotation: AnnotationDto): SessionDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        require(session.screens.any { it.screenId == annotation.screenId }) {
            "Cannot save annotation for unknown screen: ${annotation.screenId}"
        }
        val nextSequence = annotation.sequenceNumber?.plus(1) ?: session.nextItemSequenceNumber
        val updated = session.copy(
            items = session.items.filterNot { it.itemId == annotation.itemId } + annotation,
            nextItemSequenceNumber = maxOf(session.nextItemSequenceNumber, nextSequence),
            updatedAtEpochMillis = clock(),
        )
        commitSessionMutation(session, updated)
    }

    fun nextId(): String = synchronized(lock) { idGenerator() }

    fun listSessions(packageName: String? = null, includeClosed: Boolean = false): FeedbackSessionList = synchronized(lock) {
        val replaySkipped = replaySkippedSessionList(packageName, includeClosed)
        persistence?.list(packageName, includeClosed)
            ?.let { list -> list.copy(skippedSessions = list.skippedSessions + replaySkipped) }
            ?: FeedbackSessionList(
                sessions = sessions.values
                    .filter { packageName == null || it.packageName == packageName }
                    .filter { includeClosed || it.status != SessionStatusDto.CLOSED }
                    .map(FeedbackSessionSummary.Companion::from)
                    .sortedByDescending { it.updatedAtEpochMillis },
                skippedSessions = replaySkipped,
            )
    }

    fun openExistingSession(sessionId: String): SessionDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        currentSessionId = session.sessionId
        session
    }

    fun closeSession(sessionId: String): SessionDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val now = clock()
        val closed = SessionReducer.reduce(session, SessionMutation.Close(now))
        save(closed)
        sessions[sessionId] = closed
        if (currentSessionId == sessionId) currentSessionId = null
        closed
    }

    // ------------------------------------------------------------------
    // Spec'd mutations — each wraps with event-log write-ahead
    // ------------------------------------------------------------------

    fun addScreen(sessionId: String, screen: SnapshotDto): SnapshotDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val now = clock()
        val captured = screen.copy(
            screenId = if (screen.screenId == "pending") idGenerator() else screen.screenId,
            capturedAtEpochMillis = now,
        )
        val updated = session.copy(
            screens = session.screens + captured,
            updatedAtEpochMillis = now,
        )
        appendEventThenMutate(
            sessionId = sessionId,
            type = "addScreen",
            payload = buildJsonObject {
                put("sessionId", sessionId)
                put("screen", eventLogJson.encodeToJsonElement(SnapshotDto.serializer(), captured))
            },
        ) {
            save(updated)
            sessions[sessionId] = updated
        }
        captured
    }

    fun addScreenWithItems(
        sessionId: String,
        screen: SnapshotDto,
        items: List<AnnotationDto>,
        eventMetadata: JsonObject = JsonObject(emptyMap()),
    ): SessionDto = synchronized(lock) {
        require(items.isNotEmpty()) { "At least one feedback item is required" }
        val session = getSessionLocked(sessionId)
        val now = clock()
        val captured = screen.copy(
            screenId = if (screen.screenId == "pending") idGenerator() else screen.screenId,
            capturedAtEpochMillis = now,
        )
        val firstSequence = session.migratedNextItemSequenceNumber()
        val createdItems = items.mapIndexed { index, item ->
            item.copy(
                itemId = if (item.itemId == "pending") idGenerator() else item.itemId,
                screenId = captured.screenId,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                sequenceNumber = item.sequenceNumber ?: firstSequence + index,
                delivery = FeedbackDelivery.DRAFT,
            )
        }
        val nextSequence = createdItems.mapNotNull { it.sequenceNumber }.maxOrNull()?.plus(1) ?: firstSequence
        val updated = session.copy(
            screens = session.screens + captured,
            items = session.items + createdItems,
            nextItemSequenceNumber = maxOf(firstSequence, nextSequence),
            updatedAtEpochMillis = now,
        )
        appendEventThenMutate(
            sessionId = sessionId,
            type = "addScreenWithItems",
            payload = buildJsonObject {
                put("sessionId", sessionId)
                eventMetadata.forEach { (key, value) -> put(key, value) }
                put("screen", eventLogJson.encodeToJsonElement(SnapshotDto.serializer(), captured))
                put(
                    "items",
                    eventLogJson.encodeToJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(AnnotationDto.serializer()),
                        createdItems,
                    ),
                )
            },
        ) {
            commitSessionMutation(session, updated)
        }
        updated
    }

    fun deleteScreen(sessionId: String, screenId: String): SessionDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        if (session.screens.none { it.screenId == screenId }) {
            throw FeedbackSessionException("SCREEN_NOT_FOUND: Unknown screen: $screenId")
        }
        val removedItemIds = session.items
            .filter { it.screenId == screenId }
            .map { it.itemId }
            .toSet()
        val updatedBatches = session.handoffBatches
            .map { batch -> batch.copy(itemIds = batch.itemIds.filterNot { it in removedItemIds }) }
            .filter { it.itemIds.isNotEmpty() }
        val updated = session.copy(
            screens = session.screens.filterNot { it.screenId == screenId },
            items = session.items.filterNot { it.screenId == screenId },
            handoffBatches = updatedBatches,
            updatedAtEpochMillis = clock(),
        )
        appendEventThenMutate(
            sessionId = sessionId,
            type = "deleteScreen",
            payload = buildJsonObject {
                put("sessionId", sessionId)
                put("screenId", screenId)
            },
        ) {
            // Note: disk artifact deletion is NOT replayed on boot (replay is SessionDto-only).
            commitSessionMutation(session, updated).also {
                persistence?.artifactPaths()
                    ?.screenArtifactDirectory(sessionId, screenId)
                    ?.deleteRecursively()
            }
        }
        updated
    }

    fun addItem(sessionId: String, item: AnnotationDto): AnnotationDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        require(session.screens.any { it.screenId == item.screenId }) {
            "Cannot add feedback for unknown screen: ${item.screenId}"
        }
        val now = clock()
        val sequence = session.migratedNextItemSequenceNumber()
        val createdSequence = item.sequenceNumber ?: sequence
        val created = item.copy(
            itemId = idGenerator(),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            sequenceNumber = createdSequence,
            delivery = item.delivery,
        )
        val updated = session.copy(
            items = session.items + created,
            nextItemSequenceNumber = maxOf(sequence + 1, createdSequence + 1),
            updatedAtEpochMillis = now,
        )
        appendEventThenMutate(
            sessionId = sessionId,
            type = "addItem",
            payload = buildJsonObject {
                put("sessionId", sessionId)
                put("item", eventLogJson.encodeToJsonElement(AnnotationDto.serializer(), created))
            },
        ) {
            save(updated)
            sessions[sessionId] = updated
        }
        created
    }

    fun clearDraftItems(sessionId: String): SessionDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val updated = session.copy(
            items = session.items.filter { it.delivery != FeedbackDelivery.DRAFT },
            updatedAtEpochMillis = clock(),
        )
        appendEventThenMutate(
            sessionId = sessionId,
            type = "clearDraftItems",
            payload = buildJsonObject {
                put("sessionId", sessionId)
                putItems(updated.items)
            },
        ) {
            commitSessionMutation(session, updated)
        }
        updated
    }

    fun sendDraftToAgent(
        sessionId: String,
        markdownSnapshot: String?,
        targetItemIds: List<String>? = null,
    ): SessionDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val targetSet = targetItemIds?.toSet()
        val candidates = session.items.filter { item ->
            val matchesTarget = targetSet == null || item.itemId in targetSet
            matchesTarget &&
                (
                    item.delivery == FeedbackDelivery.DRAFT ||
                        (item.delivery == FeedbackDelivery.SENT && item.status == AnnotationStatusDto.READY)
                    )
        }
        if (candidates.isEmpty()) {
            throw FeedbackSessionException("NO_DRAFT_FEEDBACK: No draft feedback items to send")
        }
        val now = clock()
        val batch = buildHandoffBatch(session, candidates, markdownSnapshot, now)
        val updatedItems = buildUpdatedItemsForHandoff(session.items, targetSet, batch, now)
        val updated = session.copy(
            items = updatedItems,
            handoffBatches = session.handoffBatches + batch,
            status = SessionStatusDto.READY_FOR_AGENT,
            updatedAtEpochMillis = now,
        )
        appendEventThenMutate(
            sessionId = sessionId,
            type = "markSent",
            payload = buildJsonObject {
                put("sessionId", sessionId)
                put("batch", eventLogJson.encodeToJsonElement(FeedbackHandoffBatch.serializer(), batch))
                put(
                    "items",
                    eventLogJson.encodeToJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(AnnotationDto.serializer()),
                        updatedItems,
                    ),
                )
            },
        ) {
            commitSessionMutation(session, updated)
        }
        updated
    }

    private fun buildHandoffBatch(
        session: SessionDto,
        candidates: List<AnnotationDto>,
        markdownSnapshot: String?,
        now: Long,
    ): FeedbackHandoffBatch = FeedbackHandoffBatch(
        batchId = idGenerator(),
        sequenceNumber = session.handoffBatches.size + 1,
        createdAtEpochMillis = now,
        itemIds = candidates.map { it.itemId },
        markdownSnapshot = markdownSnapshot,
    )

    private fun buildUpdatedItemsForHandoff(
        items: List<AnnotationDto>,
        targetSet: Set<String>?,
        batch: FeedbackHandoffBatch,
        now: Long,
    ): List<AnnotationDto> = items.map { item ->
        val matchesTarget = targetSet == null || item.itemId in targetSet
        when {
            item.delivery == FeedbackDelivery.DRAFT && matchesTarget -> item.copy(
                delivery = FeedbackDelivery.SENT,
                handoffBatchId = batch.batchId,
                sentAtEpochMillis = now,
                lastHandedOffAtEpochMillis = now,
                status = AnnotationStatusDto.READY,
                updatedAtEpochMillis = now,
            )
            item.delivery == FeedbackDelivery.SENT &&
                item.status == AnnotationStatusDto.READY &&
                matchesTarget -> {
                // Re-save: preserve sentAt; refresh lastHandedOffAt + handoffBatchId.
                item.copy(
                    handoffBatchId = batch.batchId,
                    lastHandedOffAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
            }
            else -> item
        }
    }

    fun markItemsHandedOff(sessionId: String, itemIds: List<String>): SessionDto = synchronized(lock) {
        if (itemIds.isEmpty()) {
            throw FeedbackSessionException("itemIds must not be empty")
        }
        val session = getSessionLocked(sessionId)
        val targetSet = itemIds.toSet()
        val now = clock()
        val updatedItems = session.items.map { item ->
            if (item.itemId in targetSet) {
                item.copy(
                    lastHandedOffAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
            } else {
                item
            }
        }
        val updated = session.copy(
            items = updatedItems,
            updatedAtEpochMillis = now,
        )
        appendEventThenMutate(
            sessionId = sessionId,
            type = "markItemsHandedOff",
            payload = buildJsonObject {
                put("sessionId", sessionId)
                putItems(updatedItems)
            },
        ) {
            commitSessionMutation(session, updated)
        }
        updated
    }

    fun markReadyForAgent(sessionId: String): SessionDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val now = clock()
        val updated = SessionReducer.reduce(session, SessionMutation.MarkReadyForAgent(now))
        save(updated)
        sessions[sessionId] = updated
        updated
    }

    fun updateItemStatus(
        sessionId: String,
        itemId: String,
        status: AnnotationStatusDto,
        agentSummary: String?,
    ): AnnotationDto = synchronized(lock) {
        require(status in setOf(AnnotationStatusDto.RESOLVED, AnnotationStatusDto.NEEDS_CLARIFICATION, AnnotationStatusDto.WONT_FIX)) {
            "Agent resolution status is not allowed: $status"
        }
        val session = getSessionLocked(sessionId)
        val now = clock()
        var updatedItem: AnnotationDto? = null
        val updatedItems = session.items.map { item ->
            if (item.itemId == itemId) {
                item.copy(
                    status = status,
                    agentSummary = agentSummary,
                    updatedAtEpochMillis = now,
                ).also { updatedItem = it }
            } else {
                item
            }
        }
        val item = updatedItem ?: throw FeedbackSessionException("Unknown feedback item: $itemId")
        val updated = session.copy(items = updatedItems, updatedAtEpochMillis = now)
        appendEventThenMutate(
            sessionId = sessionId,
            type = "updateItemStatus",
            payload = buildJsonObject {
                put("sessionId", sessionId)
                putItems(updatedItems)
            },
        ) {
            commitSessionMutation(session, updated)
        }
        item
    }

    private val resolvedStatusSet = setOf(
        AnnotationStatusDto.RESOLVED,
        AnnotationStatusDto.WONT_FIX,
    )

    fun claimFeedback(sessionId: String, itemId: String, agentNote: String?): AnnotationDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val now = clock()
        var updatedItem: AnnotationDto? = null
        val updatedItems = session.items.map { item ->
            if (item.itemId != itemId) return@map item
            if (item.status in resolvedStatusSet) {
                throw FeedbackSessionException(
                    "ITEM_ALREADY_RESOLVED: Cannot claim resolved feedback item: $itemId",
                )
            }
            item.copy(
                status = AnnotationStatusDto.IN_PROGRESS,
                agentSummary = agentNote ?: item.agentSummary,
                updatedAtEpochMillis = now,
            ).also { updatedItem = it }
        }
        val item = updatedItem
            ?: throw FeedbackSessionException("Unknown feedback item: $itemId")
        val updated = session.copy(items = updatedItems, updatedAtEpochMillis = now)
        appendEventThenMutate(
            sessionId = sessionId,
            type = "claimFeedback",
            payload = buildJsonObject {
                put("sessionId", sessionId)
                putItems(updatedItems)
            },
        ) {
            commitSessionMutation(session, updated)
        }
        item
    }

    fun updateDraftItem(
        sessionId: String,
        itemId: String,
        label: String?,
        severity: AnnotationSeverityDto?,
        comment: String?,
        status: AnnotationStatusDto?,
    ): SessionDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val now = clock()
        var found = false
        val updatedItems = session.items.map { item ->
            if (item.itemId != itemId) return@map item
            found = true
            if (isLockedForEdit(item)) {
                throw FeedbackSessionException("ITEM_NOT_EDITABLE: Agent has claimed this item: $itemId")
            }
            item.copy(
                label = label ?: item.label,
                severity = severity ?: item.severity,
                comment = comment ?: item.comment,
                status = status ?: item.status,
                updatedAtEpochMillis = now,
            )
        }
        if (!found) throw FeedbackSessionException("Unknown feedback item: $itemId")
        val updated = session.copy(items = updatedItems, updatedAtEpochMillis = now)
        appendEventThenMutate(
            sessionId = sessionId,
            type = "updateDraftItem",
            payload = buildJsonObject {
                put("sessionId", sessionId)
                put(
                    "items",
                    eventLogJson.encodeToJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(AnnotationDto.serializer()),
                        updatedItems,
                    ),
                )
            },
        ) {
            save(updated)
            sessions[sessionId] = updated
        }
        updated
    }

    fun deleteDraftItem(sessionId: String, itemId: String): SessionDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val item = session.items.find { it.itemId == itemId }
            ?: throw FeedbackSessionException("Unknown feedback item: $itemId")
        if (isLockedForEdit(item)) {
            throw FeedbackSessionException("ITEM_NOT_EDITABLE: Agent has claimed this item: $itemId")
        }
        val updatedBatches = session.handoffBatches
            .map { batch -> batch.copy(itemIds = batch.itemIds.filterNot { it == itemId }) }
            .filter { it.itemIds.isNotEmpty() }
        val updated = session.copy(
            items = session.items.filterNot { it.itemId == itemId },
            handoffBatches = updatedBatches,
            updatedAtEpochMillis = clock(),
        )
        appendEventThenMutate(
            sessionId = sessionId,
            type = "deleteDraftItem",
            payload = buildJsonObject {
                put("sessionId", sessionId)
                put("itemId", itemId)
            },
        ) {
            save(updated)
            sessions[sessionId] = updated
        }
        updated
    }

    // ------------------------------------------------------------------
    // Event log helpers
    // ------------------------------------------------------------------

    /**
     * Appends a [SessionEvent] to the event log for [sessionId] BEFORE executing
     * [mutate]. If [EventLogException] is thrown, [mutate] is NOT called and the
     * exception propagates — leaving in-memory state unchanged.
     *
     * When [eventLogWriterProvider] is null, [mutate] is called directly (no-op path).
     */
    private inline fun appendEventThenMutate(
        sessionId: String,
        type: String,
        payload: JsonObject,
        mutate: () -> Unit,
    ) {
        // Throws EventLogException on failure — mutate() is never reached.
        journal.append(sessionId = sessionId, type = type, payload = payload)
        mutate()
    }

    private fun JsonObjectBuilder.putItems(items: List<AnnotationDto>) {
        put(
            "items",
            eventLogJson.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(AnnotationDto.serializer()),
                items,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Boot replay
    // ------------------------------------------------------------------

    /**
     * Replays all events for [sessionId] from the event log.
     *
     * Simplification (A.4): resets items, screens, and handoffBatches to empty
     * before applying events, so the snapshot and event log don't double-count.
     * Session identity fields (id, packageName, projectRoot, createdAt, status)
     * are preserved from the persistence snapshot if available.
     *
     * This method must only be called from the init block (not synchronized) since
     * [lock] is not held yet at construction time.
     */
    private fun replaySessionEvents(sessionId: String) {
        val reader = journal.reader(sessionId)
        val shell = sessions[sessionId]
        if (reader != null && shell != null) {
            val replayed = replayEngine.replay(
                sessionId = sessionId,
                shell = shell,
                reader = reader,
                recordSkipped = ::recordReplaySkippedSession,
            )
            sessions[sessionId] = replayed
        }
    }

    private fun recordReplaySkippedSession(sessionId: String, path: String, message: String) {
        replaySkippedSessions[sessionId] = SkippedFeedbackSession(path = path, message = message)
    }

    private fun replaySkippedSessionList(packageName: String?, includeClosed: Boolean): List<SkippedFeedbackSession> = replaySkippedSessions
        .filter { (sessionId, _) ->
            val session = sessions[sessionId]
            session != null &&
                (packageName == null || session.packageName == packageName) &&
                (includeClosed || session.status != SessionStatusDto.CLOSED)
        }
        .values
        .toList()

    // ------------------------------------------------------------------
    // Private helpers — unchanged
    // ------------------------------------------------------------------

    private fun isLockedForEdit(item: AnnotationDto): Boolean = item.delivery == FeedbackDelivery.SENT &&
        item.status in setOf(
            AnnotationStatusDto.IN_PROGRESS,
            AnnotationStatusDto.RESOLVED,
            AnnotationStatusDto.WONT_FIX,
        )

    private fun getSessionLocked(sessionId: String): SessionDto {
        val session = loadPersistedSessionIfAvailable(sessionId)
            ?: sessions[sessionId]
            ?: throw FeedbackSessionException("Unknown feedback session: $sessionId")
        val migrated = session.withMigratedItemSequenceCounter()
        sessions[migrated.sessionId] = migrated
        return migrated
    }

    private fun loadPersistedSessionIfAvailable(sessionId: String): SessionDto? {
        val loaded = persistence?.let { p ->
            runCatching { p.load(sessionId) }.getOrNull()
        } ?: return null
        sessions[loaded.sessionId] = loaded
        return loaded
    }

    private fun commitSessionMutation(previous: SessionDto, updated: SessionDto): SessionDto {
        save(updated)
        sessions[previous.sessionId] = updated
        return updated
    }

    private fun save(session: SessionDto) {
        persistence?.save(session)
    }
}

class FeedbackSessionException(message: String) : RuntimeException(message)
