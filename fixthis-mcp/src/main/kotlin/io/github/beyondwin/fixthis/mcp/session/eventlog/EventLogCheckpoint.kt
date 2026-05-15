package io.github.beyondwin.fixthis.mcp.session.eventlog

import kotlinx.serialization.Serializable

@Serializable
data class EventLogCheckpoint(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val compactedThroughSequenceNumber: Long,
    val snapshotUpdatedAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
)
