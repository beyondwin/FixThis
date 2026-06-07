package io.github.beyondwin.fixthis.mcp.session.lifecycle.store

import kotlinx.serialization.json.JsonObject

internal data class EventBackedMutation<T>(
    val payload: JsonObject,
    val apply: () -> T,
)
