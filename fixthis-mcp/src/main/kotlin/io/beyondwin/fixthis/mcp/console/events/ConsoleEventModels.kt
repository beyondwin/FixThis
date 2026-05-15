package io.beyondwin.fixthis.mcp.console.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ConsoleEvent(
    val id: Long,
    val name: String,
    val data: JsonObject,
    val createdAtEpochMillis: Long,
)

data class ConsoleEventReplay(
    val events: List<ConsoleEvent>,
    val overflow: Boolean,
    val oldestAvailableEventId: Long?,
)
