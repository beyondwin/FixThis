package io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog

import kotlinx.serialization.Serializable

@Serializable
data class EventLogCheckpoint(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val compactedThroughSequenceNumber: Long,
    val snapshotUpdatedAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
)
