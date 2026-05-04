package io.github.pointpatch.mcp

import io.github.pointpatch.mcp.tools.PointPatchToolException
import io.github.pointpatch.mcp.tools.PointPatchTools
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val JsonRpcVersion = "2.0"
private const val McpProtocolVersion = "2025-06-18"

class McpProtocol(private val tools: PointPatchTools = PointPatchTools()) {
    suspend fun handleLine(line: String): String? {
        val request = parseRequest(line).getOrElse {
            return response(id = null, error = errorObject(-32700, "Parse error: ${it.message}"))
        }
        val id = request["id"]
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull
            ?: return response(id, error = errorObject(-32600, "Invalid request: method is required"))

        if (method == "notifications/initialized") return null
        if (id == null && method.startsWith("notifications/")) return null

        return try {
            val result = when (method) {
                "initialize" -> initializeResult()
                "tools/list" -> buildJsonObject { put("tools", tools.listTools()) }
                "tools/call" -> {
                    val params = request.objectParams()
                    val name = params.stringParam("name")
                        ?: throw McpInvalidParamsException("tools/call params.name is required")
                    val arguments = params["arguments"]?.jsonObjectOrInvalid("tools/call params.arguments")
                        ?: JsonObject(emptyMap())
                    tools.call(name, arguments)
                }
                "resources/list" -> buildJsonObject { put("resources", tools.listResources()) }
                "resources/read" -> {
                    val params = request.objectParams()
                    val uri = params.stringParam("uri")
                        ?: throw McpInvalidParamsException("resources/read params.uri is required")
                    tools.readResource(uri)
                }
                "ping" -> JsonObject(emptyMap())
                else -> return response(id, error = errorObject(-32601, "Method not found: $method"))
            }
            response(id, result = result)
        } catch (error: McpInvalidParamsException) {
            response(id, error = errorObject(-32602, error.message ?: "Invalid params"))
        } catch (error: PointPatchToolException) {
            response(id, error = errorObject(-32602, error.message ?: "Invalid tool request"))
        } catch (error: Throwable) {
            response(id, error = errorObject(-32603, error.message ?: error::class.java.simpleName))
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

    private fun JsonObject.objectParams(): JsonObject =
        this["params"]?.jsonObjectOrInvalid("params") ?: JsonObject(emptyMap())

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
