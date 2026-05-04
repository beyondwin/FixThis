package io.github.pointpatch.mcp

import io.github.pointpatch.mcp.tools.PointPatchToolException
import io.github.pointpatch.mcp.tools.PointPatchTools
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val JsonRpcVersion = "2.0"
private const val McpProtocolVersion = "2025-06-18"

class McpProtocol(private val tools: PointPatchTools = PointPatchTools()) {
    suspend fun handleLine(line: String): String? {
        return when (val message = decodeLine(line)) {
            is McpIncoming.ImmediateResponse -> message.response
            is McpIncoming.Notification -> null
            is McpRequest -> handleRequest(message)
        }
    }

    internal fun decodeLine(line: String): McpIncoming {
        val request = parseRequest(line).getOrElse {
            return McpIncoming.ImmediateResponse(response(id = null, error = errorObject(-32700, "Parse error: ${it.message}")))
        }

        val id = request["id"]
        val responseId = id.validRequestIdOrNull()
        fun invalid(message: String): McpIncoming =
            McpIncoming.ImmediateResponse(response(responseId, error = errorObject(-32600, "Invalid Request: $message")))

        val jsonrpc = (request["jsonrpc"] as? JsonPrimitive)?.stringContentOrNull()
        if (jsonrpc != JsonRpcVersion) return invalid("jsonrpc must be 2.0")

        val method = (request["method"] as? JsonPrimitive)?.stringContentOrNull()
            ?: return invalid("method must be a string")

        if (method.startsWith("notifications/")) {
            if (request.containsKey("id")) return invalid("notifications must not include id")
            return McpIncoming.Notification(
                method = method,
                params = (request["params"] as? JsonObject) ?: JsonObject(emptyMap()),
            )
        }

        if (!request.containsKey("id")) return invalid("request id is required")
        val requestId = responseId ?: return invalid("request id must be a string or integer")

        return McpRequest(
            id = requestId,
            idKey = requestId.requestIdKey(),
            method = method,
            params = (request["params"] as? JsonObject) ?: JsonObject(emptyMap()),
        )
    }

    internal suspend fun handleRequest(request: McpRequest): String? {
        return try {
            val result = when (request.method) {
                "initialize" -> initializeResult()
                "tools/list" -> buildJsonObject { put("tools", tools.listTools()) }
                "tools/call" -> {
                    val params = request.params
                    val name = params.stringParam("name")
                        ?: throw McpInvalidParamsException("tools/call params.name is required")
                    val arguments = params["arguments"]?.jsonObjectOrInvalid("tools/call params.arguments")
                        ?: JsonObject(emptyMap())
                    tools.call(name, arguments)
                }
                "resources/list" -> buildJsonObject { put("resources", tools.listResources()) }
                "resources/read" -> {
                    val params = request.params
                    val uri = params.stringParam("uri")
                        ?: throw McpInvalidParamsException("resources/read params.uri is required")
                    tools.readResource(uri)
                }
                "ping" -> JsonObject(emptyMap())
                else -> return response(request.id, error = errorObject(-32601, "Method not found: ${request.method}"))
            }
            response(request.id, result = result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: McpInvalidParamsException) {
            response(request.id, error = errorObject(-32602, error.message ?: "Invalid params"))
        } catch (error: PointPatchToolException) {
            response(request.id, error = errorObject(-32602, error.message ?: "Invalid tool request"))
        } catch (error: Throwable) {
            response(request.id, error = errorObject(-32603, error.message ?: error::class.java.simpleName))
        }
    }

    private fun initializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", McpProtocolVersion)
        put(
            "capabilities",
            buildJsonObject {
                put("tools", JsonObject(emptyMap()))
                put("resources", JsonObject(emptyMap()))
                put("prompts", JsonObject(emptyMap()))
            },
        )
        put(
            "serverInfo",
            buildJsonObject {
                put("name", "pointpatch-mcp")
                put("version", "0.1.0")
            },
        )
    }

    private fun parseRequest(line: String): Result<JsonObject> = runCatching {
        val element = json.parseToJsonElement(line)
        element as? JsonObject ?: throw SerializationException("JSON-RPC request must be an object")
    }

    private fun JsonElement.jsonObjectOrInvalid(name: String): JsonObject =
        this as? JsonObject ?: throw McpInvalidParamsException("$name must be an object")

    private fun JsonObject.stringParam(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun response(id: JsonElement?, result: JsonElement? = null, error: JsonObject? = null): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("jsonrpc", JsonRpcVersion)
                put("id", id ?: JsonNull)
                result?.let { put("result", it) }
                error?.let { put("error", it) }
            },
        )

    private fun errorObject(code: Int, message: String): JsonObject = buildJsonObject {
        put("code", code)
        put("message", message)
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val json: Json = Json {
            explicitNulls = false
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

internal sealed interface McpIncoming {
    data class ImmediateResponse(val response: String) : McpIncoming

    data class Notification(val method: String, val params: JsonObject) : McpIncoming
}

internal data class McpRequest(
    val id: JsonPrimitive,
    val idKey: String,
    val method: String,
    val params: JsonObject,
) : McpIncoming

internal fun JsonElement?.validRequestIdOrNull(): JsonPrimitive? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive === JsonNull) return null
    if (primitive.isStringPrimitive) return primitive
    return primitive.longOrNull?.let { primitive }
}

internal fun JsonPrimitive.requestIdKey(): String =
    if (isStringPrimitive) "s:$content" else "i:${longOrNull}"

private val JsonPrimitive.isStringPrimitive: Boolean
    get() = this !== JsonNull && toString().startsWith("\"")

private fun JsonPrimitive.stringContentOrNull(): String? =
    if (isStringPrimitive) contentOrNull else null

class McpInvalidParamsException(message: String) : RuntimeException(message)

internal fun textContent(text: String, mimeType: String? = null): JsonObject = buildJsonObject {
    put("type", "text")
    mimeType?.let { put("mimeType", it) }
    put("text", text)
}

internal fun toolResult(isError: Boolean = false, content: List<JsonObject>): JsonObject = buildJsonObject {
    put("content", buildJsonArray { content.forEach { add(it) } })
    put("isError", isError)
}

internal fun resourceText(uri: String, text: String, mimeType: String = "application/json"): JsonObject = buildJsonObject {
    put(
        "contents",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("uri", uri)
                    put("mimeType", mimeType)
                    put("text", text)
                },
            )
        },
    )
}
