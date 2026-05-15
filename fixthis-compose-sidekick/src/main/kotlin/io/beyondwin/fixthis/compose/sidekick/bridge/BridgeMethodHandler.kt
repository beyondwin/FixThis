package io.beyondwin.fixthis.compose.sidekick.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal interface BridgeMethodHandler {
    val method: String
    suspend fun handle(params: JsonObject): JsonElement
}
