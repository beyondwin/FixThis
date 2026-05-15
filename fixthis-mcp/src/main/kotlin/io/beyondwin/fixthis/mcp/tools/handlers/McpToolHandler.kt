package io.beyondwin.fixthis.mcp.tools.handlers

import kotlinx.serialization.json.JsonObject

internal interface McpToolHandler {
    val name: String
    suspend fun handle(arguments: JsonObject): JsonObject
}
