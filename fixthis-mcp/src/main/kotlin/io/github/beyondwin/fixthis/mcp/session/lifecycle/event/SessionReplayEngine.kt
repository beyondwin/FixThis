package io.github.beyondwin.fixthis.mcp.session.lifecycle.event

import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.withMigratedItemSequenceCounter
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackHandoffBatch
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogCheckpoint
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogReader
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.SessionEvent
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPersistence
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

private val eventLogJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private data class ReplayCheckpointRead(
    val checkpoint: EventLogCheckpoint?,
    val shouldReplay: Boolean,
)

private data class ReplayedSessionState(
    val session: SessionDto,
    val maxSequenceNumber: Long,
    val replayedAny: Boolean,
)

@Suppress("TooManyFunctions")
class SessionReplayEngine(
    private val journal: SessionEventJournal,
    private val persistence: FeedbackSessionPersistence?,
) {
    fun replay(
        sessionId: String,
        shell: SessionDto,
        reader: EventLogReader,
        recordSkipped: (sessionId: String, path: String, message: String) -> Unit,
    ): SessionDto {
        val checkpointRead = readReplayCheckpoint(sessionId, reader, recordSkipped)
        val events = if (checkpointRead.shouldReplay) readReplayEventsOrNull(reader) else null
        if (events != null) {
            val replayed = replayEventsFrom(shell, checkpointRead.checkpoint, events)
            if (checkpointRead.checkpoint != null || events.isNotEmpty()) {
                applyReplayedSession(sessionId, replayed)
                return replayed.session
            }
        }
        return shell
    }

    // Boot replay errors degrade gracefully — see ALH-3 spec.
    // The catch is intentional: invalid checkpoints must not crash the store at startup.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun readReplayCheckpoint(
        sessionId: String,
        reader: EventLogReader,
        recordSkipped: (sessionId: String, path: String, message: String) -> Unit,
    ): ReplayCheckpointRead {
        var shouldReplay = true
        var checkpoint: EventLogCheckpoint? = null
        try {
            checkpoint = reader.readCheckpointOrNull()
        } catch (e: Exception) {
            recordInvalidReplayCheckpoint(
                sessionId = sessionId,
                reader = reader,
                message = "Invalid event log checkpoint: ${e.message ?: e::class.java.simpleName}",
                recordSkipped = recordSkipped,
            )
            shouldReplay = false
        }
        val invalidMessage = if (shouldReplay) invalidCheckpointMessage(sessionId, checkpoint) else null
        if (invalidMessage != null) {
            recordInvalidReplayCheckpoint(sessionId, reader, invalidMessage, recordSkipped)
            shouldReplay = false
        }
        return ReplayCheckpointRead(checkpoint = checkpoint.takeIf { shouldReplay }, shouldReplay = shouldReplay)
    }

    private fun invalidCheckpointMessage(sessionId: String, checkpoint: EventLogCheckpoint?): String? = when {
        checkpoint == null -> null
        checkpoint.schemaVersion != 1 ->
            "Invalid event log checkpoint: unsupported schemaVersion ${checkpoint.schemaVersion}"
        checkpoint.sessionId != sessionId ->
            "Invalid event log checkpoint: sessionId ${checkpoint.sessionId} does not match $sessionId"
        else -> null
    }

    private fun recordInvalidReplayCheckpoint(
        sessionId: String,
        reader: EventLogReader,
        message: String,
        recordSkipped: (sessionId: String, path: String, message: String) -> Unit,
    ) {
        recordSkipped(
            sessionId,
            reader.checkpointFile.absolutePath,
            message,
        )
        journal.seedNextEventSequenceFromActiveLog(sessionId, reader)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun readReplayEventsOrNull(reader: EventLogReader): List<SessionEvent>? = try {
        reader.readAll()
    } catch (e: Exception) {
        null
    }

    private fun replayEventsFrom(
        shell: SessionDto,
        checkpoint: EventLogCheckpoint?,
        events: List<SessionEvent>,
    ): ReplayedSessionState {
        var current = replayStartingSession(shell, checkpoint)
        var maxSeq = maxOf(
            journal.lastReplayedSequence(shell.sessionId),
            checkpoint?.compactedThroughSequenceNumber ?: -1L,
        )
        var replayedAny = false

        for (event in events) {
            if (event.sequenceNumber <= maxSeq) continue // idempotent guard
            current = applyEvent(current, event) ?: current
            maxSeq = event.sequenceNumber
            replayedAny = true
        }

        return ReplayedSessionState(
            session = LegacyRuntimeEvidenceReplay.restoreSnapshotOnlyItemLinks(shell, current),
            maxSequenceNumber = maxSeq,
            replayedAny = replayedAny,
        )
    }

    private fun replayStartingSession(
        shell: SessionDto,
        checkpoint: EventLogCheckpoint?,
    ): SessionDto = if (checkpoint == null) {
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

    private fun applyReplayedSession(sessionId: String, replayed: ReplayedSessionState) {
        if (replayed.replayedAny) {
            // Sync the snapshot so loadPersistedSessionIfAvailable returns replayed state.
            // Without this, read-through to persistence.load() overwrites replay on first getSession().
            persistence?.save(replayed.session)
        }
        journal.recordReplayedSequence(sessionId, replayed.maxSequenceNumber)
    }

    /**
     * Applies a single [SessionEvent] to [session] and returns the resulting [SessionDto].
     * Returns null if the event type is unknown or payload is malformed (event is skipped).
     *
     * IMPORTANT: IDs in the payload are the already-minted IDs from the original
     * write path; we do NOT call idGenerator here. This ensures replay produces
     * identical IDs to the original operation.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun applyEvent(session: SessionDto, event: SessionEvent): SessionDto? = try {
        when (event.type) {
            "addScreen" -> applyAddScreen(session, event)
            "addScreenWithItems" -> applyAddScreenWithItems(session, event)
            "deleteScreen" -> applyDeleteScreen(session, event)
            "addItem" -> applyAddItem(session, event)
            "updateDraftItem" -> applyUpdateDraftItem(session, event)
            "deleteDraftItem" -> applyDeleteDraftItem(session, event)
            "markSent" -> applyMarkSent(session, event)
            "clearDraftItems", "markItemsHandedOff", "updateItemStatus", "claimFeedback" ->
                applyReplaceItems(session, event)
            "runtimeEvidenceCaptured", "runtimeEvidencePolicyUpdated" ->
                RuntimeEvidenceEventReplayer.apply(session, event)
            else -> null // Unknown event type — skip
        }
    } catch (e: Exception) {
        null
    }

    private fun applyAddScreen(session: SessionDto, event: SessionEvent): SessionDto? {
        val screenJson = event.payload["screen"] ?: return null
        val screen = eventLogJson.decodeFromJsonElement(SnapshotDto.serializer(), screenJson)
        return if (session.screens.any { it.screenId == screen.screenId }) {
            session.copy(updatedAtEpochMillis = event.epochMillis)
        } else {
            session.copy(
                screens = session.screens + screen,
                updatedAtEpochMillis = event.epochMillis,
            )
        }
    }

    private fun applyAddScreenWithItems(session: SessionDto, event: SessionEvent): SessionDto? {
        val screenJson = event.payload["screen"]
        val itemsJson = event.payload["items"]
        if (screenJson == null || itemsJson == null) return null
        val screen = eventLogJson.decodeFromJsonElement(SnapshotDto.serializer(), screenJson)
        val items = eventLogJson.decodeFromJsonElement(
            ListSerializer(AnnotationDto.serializer()),
            itemsJson,
        )
        val existingScreenIds = session.screens.map { it.screenId }.toSet()
        val existingItemIds = session.items.map { it.itemId }.toSet()
        return session.copy(
            screens = if (screen.screenId in existingScreenIds) session.screens else session.screens + screen,
            items = session.items + items.filterNot { it.itemId in existingItemIds },
            updatedAtEpochMillis = event.epochMillis,
        ).withMigratedItemSequenceCounter()
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
        return if (session.items.any { it.itemId == item.itemId }) {
            session.copy(updatedAtEpochMillis = event.epochMillis).withMigratedItemSequenceCounter()
        } else {
            session.copy(
                items = session.items + item,
                updatedAtEpochMillis = event.epochMillis,
            ).withMigratedItemSequenceCounter()
        }
    }

    private fun applyUpdateDraftItem(session: SessionDto, event: SessionEvent): SessionDto? {
        val itemsJson = event.payload["items"] ?: return null
        val updatedItems = eventLogJson.decodeFromJsonElement(
            ListSerializer(AnnotationDto.serializer()),
            itemsJson,
        )
        return session.copy(
            items = updatedItems,
            updatedAtEpochMillis = event.epochMillis,
        ).withMigratedItemSequenceCounter()
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
            ListSerializer(AnnotationDto.serializer()),
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
        ).withMigratedItemSequenceCounter()
    }

    private fun applyReplaceItems(session: SessionDto, event: SessionEvent): SessionDto? {
        val itemsJson = event.payload["items"] ?: return null
        val updatedItems = eventLogJson.decodeFromJsonElement(
            ListSerializer(AnnotationDto.serializer()),
            itemsJson,
        )
        return session.copy(
            items = updatedItems,
            updatedAtEpochMillis = event.epochMillis,
        ).withMigratedItemSequenceCounter()
    }
}
