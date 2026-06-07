@file:Suppress("MaxLineLength")

package io.github.beyondwin.fixthis.mcp.session.lifecycle.store

import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationSeverityDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.migratedNextItemSequenceNumber
import io.github.beyondwin.fixthis.mcp.session.dto.withMigratedItemSequenceCounter
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackDelivery
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.FeedbackSessionHandoffMutation
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionBootReplayer
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionEventJournal
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionEventPayloadFactory
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionMutation
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionMutationService
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionReducer
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionReplayEngine
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogCompactionTask
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogReader
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogWriter
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.SessionCompactionCoordinator
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Machine-readable prefix for the rejection raised when a mutation targets a closed
 * feedback session. The prefix is the stable contract; the human-readable suffix is not.
 * Single-sourced here so every entry point (and the console HTTP mapping) cannot drift.
 */
internal const val SESSION_CLOSED_PREFIX = "SESSION_CLOSED:"

private fun sessionClosed(type: String): FeedbackSessionException = FeedbackSessionException("$SESSION_CLOSED_PREFIX Cannot run $type on a closed feedback session.")

/**
 * In-memory store for feedback sessions, with optional write-ahead event log support.
 *
 * When [eventLogWriterProvider] is non-null, every spec'd mutation appends a
 * [SessionEvent] BEFORE updating in-memory state. If the append throws
 * [io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogException], memory remains
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
// TooManyFunctions/LongParameterList kept by design: this facade-delegate exposes the wide public
// session-operation surface and a constructor whose arity is bound to the FeedbackSessionStore facade.
@Suppress("LongParameterList", "TooManyFunctions")
internal class FeedbackSessionStoreDelegate(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val persistence: FeedbackSessionPersistence? = null,
    private val eventLogWriterProvider: ((sessionId: String) -> EventLogWriter)? = null,
    private val eventLogReaderProvider: ((sessionId: String) -> EventLogReader)? = null,
    private val eventLogCompactorProvider: ((sessionId: String) -> EventLogCompactionTask)? = null,
    private val eventLogCompactionThreshold: Int = 1000,
    private val compactionFailureSink: ((sessionId: String, cause: Throwable) -> Unit)? = null,
) {
    private val lock = Any()
    private val store = SessionStateStore(persistence)
    private var currentSessionId: String? = null
    private val journal = SessionEventJournal(
        clock = clock,
        idGenerator = idGenerator,
        writerProvider = eventLogWriterProvider,
        readerProvider = eventLogReaderProvider,
    )
    private val replayEngine = SessionReplayEngine(journal, persistence)
    private val bootReplayer = SessionBootReplayer(
        replayEngine = replayEngine,
        persistence = persistence,
        hasEventLog = eventLogReaderProvider != null,
    )
    private val compactionCoordinator = SessionCompactionCoordinator(
        eventLogCompactorProvider = eventLogCompactorProvider,
        eventLogCompactionThreshold = eventLogCompactionThreshold,
        compactionFailureSink = compactionFailureSink,
        clock = clock,
    )
    private val mutations = SessionMutationService(clock, idGenerator)
    private val artifactJanitor = SessionArtifactJanitor(persistence)

    init {
        // Boot reconstruction (persistence preload + optional event-log replay) is delegated to
        // SessionBootReplayer. It runs lock-free at construction (lock is not held yet) and returns
        // the derived current-session pointer; currentSessionId stays a delegate field.
        currentSessionId = bootReplayer.replayAll(store, journal).currentSessionId
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
        store.saveAndPut(session)
        currentSessionId = session.sessionId
        session
    }

    fun currentSession(): SessionDto? = synchronized(lock) { currentSessionId?.let { getSessionLocked(it) } }

    fun getSession(sessionId: String): SessionDto = synchronized(lock) {
        getSessionLocked(sessionId)
    }

    // Domain session-save seam for the SessionRepository/domain-mapping layer (snapshot &
    // annotation repos, and McpDomainRepositoryTest). Retained after R-4 removed the prod-dead
    // resolve/claim use-cases and their McpSessionRepository wrapper.
    fun replaceSessionForDomain(session: SessionDto): SessionDto = synchronized(lock) {
        val migrated = session.withMigratedItemSequenceCounter()
        store.saveAndPut(migrated)
        // A domain save replaces session state; it must not hijack the current-session
        // pointer (consistent with commitSessionMutation). Only clear it when the
        // currently-selected session is the one being closed.
        if (migrated.status == SessionStatusDto.CLOSED && currentSessionId == migrated.sessionId) {
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

    fun listSessions(
        packageName: String? = null,
        includeClosed: Boolean = false,
    ): FeedbackSessionList = synchronized(lock) {
        val replaySkipped = bootReplayer.skippedList(packageName, includeClosed)
        persistence?.list(packageName, includeClosed)
            ?.let { list -> list.copy(skippedSessions = list.skippedSessions + replaySkipped) }
            ?: FeedbackSessionList(
                sessions = store.all()
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
        store.saveAndPut(closed)
        if (currentSessionId == sessionId) currentSessionId = null
        closed
    }

    // ------------------------------------------------------------------
    // Spec'd mutations — each wraps with event-log write-ahead
    // ------------------------------------------------------------------

    fun addScreen(sessionId: String, screen: SnapshotDto): SnapshotDto = withEventBackedMutation(sessionId, "addScreen") {
        val session = getSessionLocked(sessionId)
        val (updated, captured) = mutations.addScreen(session, screen)
        SessionEventPayloadFactory.screen(sessionId, captured) to {
            store.saveAndPut(updated)
            captured
        }
    }

    fun addScreenWithItems(
        sessionId: String,
        screen: SnapshotDto,
        items: List<AnnotationDto>,
        eventMetadata: JsonObject = JsonObject(emptyMap()),
    ): SessionDto = withOptionalEventBackedMutation(
        sessionId = sessionId,
        type = "addScreenWithItems",
        noop = { getSessionLocked(sessionId) },
        prepare = {
            require(items.isNotEmpty()) { "At least one feedback item is required" }
            require(items.all { it.comment.isNotBlank() }) { "addScreenWithItems received items with blank comment" }
            val session = getSessionLocked(sessionId)
            val existingClientKeys = session.items.mapNotNull { it.clientDraftKey() }.toSet()
            val newItems = items.filterNot { item ->
                item.clientDraftKey()?.let { it in existingClientKeys } == true
            }
            if (newItems.isEmpty()) {
                return@withOptionalEventBackedMutation null
            }
            val duplicateItems = matchingClientDraftItems(session, items)
            val now = clock()
            val captured = screenForIncomingBatch(
                session = session,
                duplicateItems = duplicateItems,
                requestedScreen = screen,
                idGenerator = idGenerator,
                now = now,
            )
            val createdItems = createScreenItems(session, captured, newItems, now, idGenerator)
            val firstSequence = session.migratedNextItemSequenceNumber()
            val nextSequence = createdItems.mapNotNull { it.sequenceNumber }.maxOrNull()?.plus(1) ?: firstSequence
            val screens = appendScreenIfMissing(session, captured)
            val updated = session.copy(
                screens = screens,
                items = session.items + createdItems,
                nextItemSequenceNumber = maxOf(firstSequence, nextSequence),
                updatedAtEpochMillis = now,
            )
            SessionEventPayloadFactory.screenWithItems(
                sessionId = sessionId,
                eventMetadata = eventMetadata,
                screen = captured,
                items = createdItems,
            ) to { commitSessionMutation(session, updated) }
        },
    )

    fun deleteScreen(sessionId: String, screenId: String): SessionDto = withEventBackedMutation(sessionId, "deleteScreen") {
        val session = getSessionLocked(sessionId)
        val updated = mutations.deleteScreen(session, screenId)
        SessionEventPayloadFactory.deleteScreen(sessionId, screenId) to {
            // Note: disk artifact deletion is NOT replayed on boot (replay is SessionDto-only).
            commitSessionMutation(session, updated).also {
                artifactJanitor.deleteScreenArtifacts(sessionId, screenId)
            }
        }
    }

    fun addItem(sessionId: String, item: AnnotationDto): AnnotationDto = withEventBackedMutation(sessionId, "addItem") {
        val session = getSessionLocked(sessionId)
        val (updated, created) = mutations.addItem(session, item)
        SessionEventPayloadFactory.item(sessionId, created) to {
            store.saveAndPut(updated)
            created
        }
    }

    fun clearDraftItems(sessionId: String): SessionDto = withEventBackedMutation(sessionId, "clearDraftItems") {
        val session = getSessionLocked(sessionId)
        val updated = session.copy(
            items = session.items.filter { it.delivery != FeedbackDelivery.DRAFT },
            updatedAtEpochMillis = clock(),
        )
        SessionEventPayloadFactory.items(sessionId, updated.items) to {
            commitSessionMutation(session, updated)
        }
    }

    fun sendDraftToAgent(
        sessionId: String,
        markdownSnapshot: String?,
        targetItemIds: List<String>? = null,
    ): SessionDto = withEventBackedMutation(sessionId, "markSent") {
        val session = getSessionLocked(sessionId)
        val now = clock()
        val prepared = FeedbackSessionHandoffMutation.prepare(
            session = session,
            targetItemIds = targetItemIds,
            markdownSnapshot = markdownSnapshot,
            now = now,
            batchId = idGenerator,
        ) ?: throw FeedbackSessionException("NO_DRAFT_FEEDBACK: No draft feedback items to send")
        val batch = prepared.batch
        val updated = prepared.session
        SessionEventPayloadFactory.handoff(sessionId, batch, updated.items) to {
            commitSessionMutation(session, updated)
        }
    }

    fun markItemsHandedOff(sessionId: String, itemIds: List<String>): SessionDto = withEventBackedMutation(sessionId, "markItemsHandedOff") {
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
        SessionEventPayloadFactory.items(sessionId, updatedItems) to {
            commitSessionMutation(session, updated)
        }
    }

    fun markReadyForAgent(sessionId: String): SessionDto = synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val now = clock()
        val updated = SessionReducer.reduce(session, SessionMutation.MarkReadyForAgent(now))
        store.saveAndPut(updated)
        updated
    }

    fun updateItemStatus(
        sessionId: String,
        itemId: String,
        status: AnnotationStatusDto,
        agentSummary: String?,
    ): AnnotationDto = withEventBackedMutation(sessionId, "updateItemStatus") {
        val session = getSessionLocked(sessionId)
        val (updated, item) = mutations.updateItemStatus(session, itemId, status, agentSummary)
        SessionEventPayloadFactory.items(sessionId, updated.items) to {
            commitSessionMutation(session, updated)
            item
        }
    }

    private val resolvedStatusSet = setOf(
        AnnotationStatusDto.RESOLVED,
        AnnotationStatusDto.WONT_FIX,
    )

    fun claimFeedback(sessionId: String, itemId: String, agentNote: String?): AnnotationDto = withEventBackedMutation(sessionId, "claimFeedback") {
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
        SessionEventPayloadFactory.items(sessionId, updatedItems) to {
            commitSessionMutation(session, updated)
            item
        }
    }

    fun updateDraftItem(
        sessionId: String,
        itemId: String,
        label: String?,
        severity: AnnotationSeverityDto?,
        comment: String?,
        status: AnnotationStatusDto?,
    ): SessionDto = withEventBackedMutation(sessionId, "updateDraftItem") {
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
        SessionEventPayloadFactory.updateDraftItems(sessionId, updatedItems) to {
            store.saveAndPut(updated)
            updated
        }
    }

    fun deleteDraftItem(sessionId: String, itemId: String): SessionDto = withEventBackedMutation(sessionId, "deleteDraftItem") {
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
        SessionEventPayloadFactory.deleteItem(sessionId, itemId) to {
            store.saveAndPut(updated)
            updated
        }
    }

    // ------------------------------------------------------------------
    // Event log helpers
    // ------------------------------------------------------------------

    private fun <T> withEventBackedMutation(
        sessionId: String,
        type: String,
        prepare: () -> Pair<JsonObject, () -> T>,
    ): T {
        val result = synchronized(lock) {
            requireOpenSessionForMutation(sessionId, type)
            val (payload, mutate) = prepare()
            // Throws EventLogException on failure, so mutate() is never reached.
            journal.append(sessionId = sessionId, type = type, payload = payload)
            mutate()
        }
        compactionCoordinator.compactAfterMutation(sessionId)
        return result
    }

    private fun <T> withOptionalEventBackedMutation(
        sessionId: String,
        type: String,
        prepare: () -> Pair<JsonObject, () -> T>?,
        noop: () -> T,
    ): T {
        val result = synchronized(lock) {
            requireOpenSessionForMutation(sessionId, type)
            val (payload, mutate) = prepare() ?: return@synchronized noop()
            journal.append(sessionId = sessionId, type = type, payload = payload)
            mutate()
        }
        compactionCoordinator.compactAfterMutation(sessionId)
        return result
    }

    private fun requireOpenSessionForMutation(sessionId: String, type: String) {
        val session = getSessionLocked(sessionId)
        if (session.status == SessionStatusDto.CLOSED) {
            throw sessionClosed(type)
        }
    }

    // ------------------------------------------------------------------
    // Private helpers — unchanged
    // ------------------------------------------------------------------

    private fun isLockedForEdit(item: AnnotationDto): Boolean = item.delivery == FeedbackDelivery.SENT &&
        item.status in setOf(
            AnnotationStatusDto.IN_PROGRESS,
            AnnotationStatusDto.RESOLVED,
            AnnotationStatusDto.WONT_FIX,
        )

    private fun getSessionLocked(sessionId: String): SessionDto = store.get(sessionId)

    private fun commitSessionMutation(previous: SessionDto, updated: SessionDto): SessionDto = store.commit(previous, updated)
}

class FeedbackSessionException(message: String) : RuntimeException(message)
