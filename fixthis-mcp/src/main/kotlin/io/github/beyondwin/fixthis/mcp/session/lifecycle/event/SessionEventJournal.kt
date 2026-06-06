package io.github.beyondwin.fixthis.mcp.session.lifecycle.event

import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogReader
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogWriter
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.SessionEvent
import kotlinx.serialization.json.JsonObject

class SessionEventJournal(
    private val clock: () -> Long,
    private val idGenerator: () -> String,
    private val writerProvider: ((sessionId: String) -> EventLogWriter)?,
    private val readerProvider: ((sessionId: String) -> EventLogReader)?,
) {
    // Tracks the highest sequence number replayed per session (for idempotent replay).
    private val lastReplayedSeq = mutableMapOf<String, Long>()

    // Per-session sequence counter for new events. Initialized lazily to
    // (maxReplayedSeq + 1) after boot replay, then monotonically incremented.
    private val nextSeqMap = mutableMapOf<String, Long>()

    fun reader(sessionId: String): EventLogReader? = readerProvider?.invoke(sessionId)

    fun append(sessionId: String, type: String, payload: JsonObject) {
        if (writerProvider != null) {
            val event = SessionEvent(
                eventId = idGenerator(),
                sequenceNumber = nextEventSeq(sessionId),
                epochMillis = clock(),
                actor = "mcp",
                type = type,
                payload = payload,
            )
            writerProvider.invoke(sessionId).append(event)
        }
    }

    fun lastReplayedSequence(sessionId: String): Long = lastReplayedSeq.getOrDefault(sessionId, -1L)

    fun recordReplayedSequence(sessionId: String, maxSequenceNumber: Long) {
        lastReplayedSeq[sessionId] = maxSequenceNumber
        nextSeqMap[sessionId] = maxSequenceNumber + 1L
    }

    fun seedNextEventSequenceFromActiveLog(sessionId: String, reader: EventLogReader) {
        val maxActiveSeq = reader.maxActiveSequenceNumberOrNull() ?: return
        val maxSeq = maxOf(lastReplayedSeq.getOrDefault(sessionId, -1L), maxActiveSeq)
        recordReplayedSequence(sessionId, maxSeq)
    }

    /** Returns and increments the next event sequence number for a session. */
    private fun nextEventSeq(sessionId: String): Long {
        val current = nextSeqMap.getOrDefault(sessionId, lastReplayedSeq.getOrDefault(sessionId, -1L) + 1L)
        nextSeqMap[sessionId] = current + 1L
        return current
    }
}
