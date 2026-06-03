package io.github.beyondwin.fixthis.mcp.tools

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.textContent
import io.github.beyondwin.fixthis.mcp.toolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

// Shared mechanics for MCP tool operation classes: uniform error normalization,
// JSON tool-result envelopes, and argument extraction. These are stateless, so
// they live as package-internal top-level helpers rather than on any one class.

@Suppress("SwallowedException", "TooGenericExceptionCaught")
internal suspend fun bridgeToolResult(block: suspend () -> JsonObject): JsonObject = try {
    block()
} catch (error: FixThisToolException) {
    throw error
} catch (error: CancellationException) {
    throw error
} catch (error: IllegalArgumentException) {
    throw FixThisToolException(error.message ?: "Invalid FixThis tool arguments")
} catch (error: Throwable) {
    toolResult(
        isError = true,
        content = listOf(textContent(error.message ?: error::class.java.simpleName)),
    )
}

internal fun jsonToolResult(payload: JsonObject): JsonObject = toolResult(
    content = listOf(
        textContent(
            text = fixThisJson.encodeToString(JsonObject.serializer(), payload),
            mimeType = "application/json",
        ),
    ),
)

internal fun JsonObject.stringParam(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.booleanParam(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.floatParam(name: String): Float? = (this[name] as? JsonPrimitive)?.floatOrNull
