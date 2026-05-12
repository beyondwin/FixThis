package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.mcp.session.eventlog.EventLogReader
import io.beyondwin.fixthis.mcp.session.eventlog.EventLogWriter
import io.beyondwin.fixthis.mcp.session.eventlog.SessionEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
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

    // Tracks the highest sequence number replayed per session (for idempotent replay).
    private val lastReplayedSeq = mutableMapOf<String, Long>()

    // Per-session sequence counter for new events. Initialized lazily to
    // (maxReplayedSeq + 1) after boot replay, then monotonically incremented.
    private val nextSeqMap = mutableMapOf<String, Long>()
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
        val closed = session.copy(
            status = SessionStatusDto.CLOSED,
            updatedAtEpochMillis = now,
        )
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

    fun addScreenWithItems(sessionId: String, screen: SnapshotDto, items: List<AnnotationDto>): SessionDto = synchronized(lock) {
        require(items.isNotEmpty()) { "At least one feedback item is required" }
        val session = getSessionLocked(sessionId)
        val now = clock()
        val captured = screen.copy(
            screenId = if (screen.screenId == "pending") idGenerator() else screen.screenId,
            capturedAtEpochMillis = now,
        )
        val firstSequence = nextItemSequenceNumber(session)
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
        val updated = session.copy(
            screens = session.screens + captured,
            items = session.items + createdItems,
            updatedAtEpochMillis = now,
        )
        appendEventThenMutate(
            sessionId = sessionId,
            type = "addScreenWithItems",
            payload = buildJsonObject {
                put("sessionId", sessionId)
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
        val created = item.copy(
            itemId = idGenerator(),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            sequenceNumber = item.sequenceNumber ?: nextItemSequenceNumber(session),
            delivery = item.delivery,
        )
        val updated = session.copy(
            items = session.items + created,
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
        commitSessionMutation(session, updated)
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
        commitSessionMutation(session, updated)
    }

    fun markReadyForAgent(sessionId: String): SessionDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val now = clock()
        val updated = session.copy(
            status = SessionStatusDto.READY_FOR_AGENT,
            updatedAtEpochMillis = now,
        )
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
        save(updated)
        sessions[sessionId] = updated
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
        save(updated)
        sessions[sessionId] = updated
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
        if (eventLogWriterProvider != null) {
            val event = SessionEvent(
                eventId = idGenerator(),
                sequenceNumber = nextEventSeq(sessionId),
                epochMillis = clock(),
                actor = "mcp",
                type = type,
                payload = payload,
            )
            // Throws EventLogException on failure — mutate() is never reached.
            eventLogWriterProvider.invoke(sessionId).append(event)
        }
        mutate()
    }

    /** Returns and increments the next event sequence number for a session. */
    private fun nextEventSeq(sessionId: String): Long {
        val current = nextSeqMap.getOrDefault(sessionId, lastReplayedSeq.getOrDefault(sessionId, -1L) + 1L)
        nextSeqMap[sessionId] = current + 1L
        return current
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
        val reader = eventLogReaderProvider?.invoke(sessionId)
        val shell = sessions[sessionId]
        if (reader == null || shell == null) return

        // Boot replay errors degrade gracefully — see ALH-3 spec.
        // The catch is intentional: replay failure must not crash the store at startup.
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val checkpoint = try {
            reader.readCheckpointOrNull()
        } catch (e: Exception) {
            recordReplaySkippedSession(
                sessionId = sessionId,
                path = reader.checkpointFile.absolutePath,
                message = "Invalid event log checkpoint: ${e.message ?: e::class.java.simpleName}",
            )
            seedNextEventSequenceFromActiveLog(sessionId, reader)
            return
        }
        if (checkpoint != null && checkpoint.schemaVersion != 1) {
            recordReplaySkippedSession(
                sessionId = sessionId,
                path = reader.checkpointFile.absolutePath,
                message = "Invalid event log checkpoint: unsupported schemaVersion ${checkpoint.schemaVersion}",
            )
            seedNextEventSequenceFromActiveLog(sessionId, reader)
            return
        }
        if (checkpoint != null && checkpoint.sessionId != sessionId) {
            recordReplaySkippedSession(
                sessionId = sessionId,
                path = reader.checkpointFile.absolutePath,
                message = "Invalid event log checkpoint: sessionId ${checkpoint.sessionId} does not match $sessionId",
            )
            seedNextEventSequenceFromActiveLog(sessionId, reader)
            return
        }
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val events = try {
            reader.readAll()
        } catch (e: Exception) {
            return
        }
        var current = if (checkpoint == null) {
            // Legacy/full-log replay: mutable session state comes entirely from events.
            shell.copy(
                screens = emptyList(),
                items = emptyList(),
                handoffBatches = emptyList(),
            )
        } else {
            // Checkpoint replay: session.json is already the compacted-through snapshot.
            shell
        }
        val startingSeq = checkpoint?.compactedThroughSequenceNumber ?: -1L
        var maxSeq = maxOf(lastReplayedSeq.getOrDefault(sessionId, -1L), startingSeq)
        var replayedAny = false

        for (event in events) {
            if (event.sequenceNumber <= maxSeq) continue // idempotent guard
            current = applyEvent(current, event) ?: current
            maxSeq = event.sequenceNumber
            replayedAny = true
        }

        if (checkpoint != null || events.isNotEmpty()) {
            sessions[sessionId] = current
            if (replayedAny) {
                // Sync the snapshot so loadPersistedSessionIfAvailable returns replayed state.
                // Without this, read-through to persistence.load() overwrites replay on first getSession().
                persistence?.save(current)
            }
            lastReplayedSeq[sessionId] = maxSeq
            // Seed sequence counter at (maxSeq + 1) so new events don't collide.
            nextSeqMap[sessionId] = maxSeq + 1L
        }
    }

    private fun recordReplaySkippedSession(sessionId: String, path: String, message: String) {
        replaySkippedSessions[sessionId] = SkippedFeedbackSession(path = path, message = message)
    }

    private fun seedNextEventSequenceFromActiveLog(sessionId: String, reader: EventLogReader) {
        val maxActiveSeq = reader.maxActiveSequenceNumberOrNull() ?: return
        val maxSeq = maxOf(lastReplayedSeq.getOrDefault(sessionId, -1L), maxActiveSeq)
        lastReplayedSeq[sessionId] = maxSeq
        nextSeqMap[sessionId] = maxSeq + 1L
    }

    private fun replaySkippedSessionList(packageName: String?, includeClosed: Boolean): List<SkippedFeedbackSession> =
        replaySkippedSessions
            .filter { (sessionId, _) ->
                val session = sessions[sessionId]
                session != null &&
                    (packageName == null || session.packageName == packageName) &&
                    (includeClosed || session.status != SessionStatusDto.CLOSED)
            }
            .values
            .toList()

    /**
     * Applies a single [SessionEvent] to [session] and returns the resulting [SessionDto].
     * Returns null if the event type is unknown or payload is malformed (event is skipped).
     *
     * IMPORTANT: IDs in the payload are the already-minted IDs from the original
     * write path; we do NOT call idGenerator here. This ensures replay produces
     * identical IDs to the original operation.
     */
    private fun applyEvent(session: SessionDto, event: SessionEvent): SessionDto? = when (event.type) {
        "addScreen" -> applyAddScreen(session, event)
        "addScreenWithItems" -> applyAddScreenWithItems(session, event)
        "deleteScreen" -> applyDeleteScreen(session, event)
        "addItem" -> applyAddItem(session, event)
        "updateDraftItem" -> applyUpdateDraftItem(session, event)
        "deleteDraftItem" -> applyDeleteDraftItem(session, event)
        "markSent" -> applyMarkSent(session, event)
        else -> null // Unknown event type — skip
    }

    private fun applyAddScreen(session: SessionDto, event: SessionEvent): SessionDto? {
        val screenJson = event.payload["screen"] ?: return null
        val screen = eventLogJson.decodeFromJsonElement(SnapshotDto.serializer(), screenJson)
        if (session.screens.any { it.screenId == screen.screenId }) {
            return session.copy(updatedAtEpochMillis = event.epochMillis)
        }
        return session.copy(
            screens = session.screens + screen,
            updatedAtEpochMillis = event.epochMillis,
        )
    }

    private fun applyAddScreenWithItems(session: SessionDto, event: SessionEvent): SessionDto? {
        val screenJson = event.payload["screen"]
        val itemsJson = event.payload["items"]
        if (screenJson == null || itemsJson == null) return null
        val screen = eventLogJson.decodeFromJsonElement(SnapshotDto.serializer(), screenJson)
        val items = eventLogJson.decodeFromJsonElement(
            kotlinx.serialization.builtins.ListSerializer(AnnotationDto.serializer()),
            itemsJson,
        )
        val existingScreenIds = session.screens.map { it.screenId }.toSet()
        val existingItemIds = session.items.map { it.itemId }.toSet()
        return session.copy(
            screens = if (screen.screenId in existingScreenIds) session.screens else session.screens + screen,
            items = session.items + items.filterNot { it.itemId in existingItemIds },
            updatedAtEpochMillis = event.epochMillis,
        )
    }

    private fun applyDeleteScreen(session: SessionDto, event: SessionEvent): SessionDto? {
        val screenId = event.payload["screenId"]?.jsonPrimitive?.content ?: return null
        val removedItemIds = session.items
            .filter { it.screenId == screenId }
            .map { it.itemId }
            .toSet()
        val updatedBatches = session.handoffBatches
            .map { batch -> batch.copy(itemIds = batch.itemIds.filterNot { it in removedItemIds }) }
            .filter { it.itemIds.isNotEmpty() }
        // Disk artifact deletion is NOT replayed.
        return session.copy(
            screens = session.screens.filterNot { it.screenId == screenId },
            items = session.items.filterNot { it.screenId == screenId },
            handoffBatches = updatedBatches,
            updatedAtEpochMillis = event.epochMillis,
        )
    }

    private fun applyAddItem(session: SessionDto, event: SessionEvent): SessionDto? {
        val itemJson = event.payload["item"] ?: return null
        val item = eventLogJson.decodeFromJsonElement(AnnotationDto.serializer(), itemJson)
        if (session.items.any { it.itemId == item.itemId }) {
            return session.copy(updatedAtEpochMillis = event.epochMillis)
        }
        return session.copy(
            items = session.items + item,
            updatedAtEpochMillis = event.epochMillis,
        )
    }

    private fun applyUpdateDraftItem(session: SessionDto, event: SessionEvent): SessionDto? {
        val itemsJson = event.payload["items"] ?: return null
        val updatedItems = eventLogJson.decodeFromJsonElement(
            kotlinx.serialization.builtins.ListSerializer(AnnotationDto.serializer()),
            itemsJson,
        )
        return session.copy(
            items = updatedItems,
            updatedAtEpochMillis = event.epochMillis,
        )
    }

    private fun applyDeleteDraftItem(session: SessionDto, event: SessionEvent): SessionDto? {
        val itemId = event.payload["itemId"]?.jsonPrimitive?.content ?: return null
        val updatedBatches = session.handoffBatches
            .map { batch -> batch.copy(itemIds = batch.itemIds.filterNot { it == itemId }) }
            .filter { it.itemIds.isNotEmpty() }
        return session.copy(
            items = session.items.filterNot { it.itemId == itemId },
            handoffBatches = updatedBatches,
            updatedAtEpochMillis = event.epochMillis,
        )
    }

    private fun applyMarkSent(session: SessionDto, event: SessionEvent): SessionDto? {
        val itemsJson = event.payload["items"]
        val batchJson = event.payload["batch"]
        if (itemsJson == null || batchJson == null) return null
        val updatedItems = eventLogJson.decodeFromJsonElement(
            kotlinx.serialization.builtins.ListSerializer(AnnotationDto.serializer()),
            itemsJson,
        )
        val batch = eventLogJson.decodeFromJsonElement(FeedbackHandoffBatch.serializer(), batchJson)
        val updatedBatches = if (session.handoffBatches.any { it.batchId == batch.batchId }) {
            session.handoffBatches.map { existing -> if (existing.batchId == batch.batchId) batch else existing }
        } else {
            session.handoffBatches + batch
        }
        return session.copy(
            items = updatedItems,
            handoffBatches = updatedBatches,
            status = SessionStatusDto.READY_FOR_AGENT,
            updatedAtEpochMillis = event.epochMillis,
        )
    }

    // ------------------------------------------------------------------
    // Private helpers — unchanged
    // ------------------------------------------------------------------

    private fun isLockedForEdit(item: AnnotationDto): Boolean = item.delivery == FeedbackDelivery.SENT &&
        item.status in setOf(AnnotationStatusDto.IN_PROGRESS, AnnotationStatusDto.RESOLVED)

    private fun getSessionLocked(sessionId: String): SessionDto = loadPersistedSessionIfAvailable(sessionId)
        ?: sessions[sessionId]
        ?: throw FeedbackSessionException("Unknown feedback session: $sessionId")

    private fun loadPersistedSessionIfAvailable(sessionId: String): SessionDto? {
        val loaded = persistence?.let { p ->
            runCatching { p.load(sessionId) }.getOrNull()
        } ?: return null
        sessions[loaded.sessionId] = loaded
        return loaded
    }

    private fun nextItemSequenceNumber(session: SessionDto): Int = session.items.mapNotNull { it.sequenceNumber }.maxOrNull()?.plus(1)
        ?: session.items.size + 1

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
