package io.github.beyondwin.fixthis.compose.sidekick.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal class BridgeRequestRouter(
    handlers: List<BridgeMethodHandler>,
) {
    private val handlersByMethod = handlers.associateBy { it.method }

    suspend fun route(method: String, params: JsonObject): JsonElement? = handlersByMethod[method]?.handle(params)
}
