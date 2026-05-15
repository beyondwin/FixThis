package io.github.beyondwin.fixthis.mcp.session.eventlog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SessionEvent(
    val version: Int = 1,
    val eventId: String,
    val sequenceNumber: Long,
    val epochMillis: Long,
    val actor: String,
    val type: String,
    val payload: JsonObject,
    val parentEventId: String? = null,
)
