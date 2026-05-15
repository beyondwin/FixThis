package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.mcp.session.AnnotationDto
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationResult
import io.beyondwin.fixthis.mcp.session.FeedbackSessionList
import io.beyondwin.fixthis.mcp.session.SessionDto
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.net.URLDecoder

@Serializable
internal data class ConsoleErrorBody(
    val error: String,
    val message: String? = null,
    val action: String? = null,
    val frozenFingerprint: String? = null,
    val currentFingerprint: String? = null,
)

internal fun HttpExchange.sendErrorJson(status: Int, message: String) {
    sendErrorJson(status = status, error = message, message = message)
}

internal fun HttpExchange.sendErrorJson(
    status: Int,
    error: String,
    message: String? = null,
    action: String? = null,
) {
    sendText(
        status,
        fixThisJson.encodeToString(
            ConsoleErrorBody.serializer(),
            ConsoleErrorBody(error = error, message = message, action = action),
        ),
        "application/json; charset=utf-8",
    )
}

internal fun Throwable.isClientDisconnect(): Boolean {
    val messages = sequenceOf(this) + suppressed.asSequence() + generateSequence(cause) { it.cause }
    return messages.any { error ->
        val message = error.message?.lowercase().orEmpty()
        message.contains("connection reset") ||
            message.contains("broken pipe") ||
            message.contains("stream is closed") ||
            message.contains("insufficient bytes written to stream")
    }
}

internal fun HttpExchange.closeQuietly() {
    runCatching { close() }
}

internal fun HttpExchange.trySendErrorJson(status: Int, message: String) {
    runCatching { sendErrorJson(status, message) }
        .onFailure { error ->
            if (error.isClientDisconnect()) {
                closeQuietly()
            } else {
                throw error
            }
        }
}

internal fun HttpExchange.trySendErrorJson(
    status: Int,
    error: String,
    message: String? = null,
    action: String? = null,
) {
    runCatching {
        sendErrorJson(
            status = status,
            error = error,
            message = message,
            action = action,
        )
    }.onFailure { cause ->
        if (cause.isClientDisconnect()) {
            closeQuietly()
        } else {
            throw cause
        }
    }
}

internal fun HttpExchange.requireMethod(method: String, block: () -> Unit) {
    if (requestMethod != method) {
        responseHeaders.add("Allow", method)
        sendErrorJson(405, "Method not allowed")
        return
    }
    block()
}

internal fun HttpExchange.queryParameter(name: String): String? = requestURI.rawQuery
    ?.split("&")
    ?.firstNotNullOfOrNull { parameter ->
        val pieces = parameter.split("=", limit = 2)
        URLDecoder.decode(pieces[0], Charsets.UTF_8.name())
            .takeIf { it == name }
            ?.let {
                URLDecoder.decode(pieces.getOrElse(1) { "" }, Charsets.UTF_8.name())
            }
    }

internal fun HttpExchange.queryBoolean(name: String): Boolean {
    val value = queryParameter(name) ?: return false
    return value.toBooleanStrictOrNull()
        ?: throw FeedbackConsoleHttpException(400, "Invalid boolean query parameter: $name")
}

internal fun HttpExchange.sendJson(statusCode: Int, value: SessionDto) {
    val enriched = enrichSessionWithStaleness(value)
    sendText(statusCode, fixThisJson.encodeToString(JsonObject.serializer(), enriched), "application/json; charset=utf-8")
}

internal fun HttpExchange.sendJson(statusCode: Int, value: FeedbackSessionList) {
    sendText(statusCode, fixThisJson.encodeToString(FeedbackSessionList.serializer(), value), "application/json; charset=utf-8")
}

internal fun HttpExchange.sendJson(statusCode: Int, value: SnapshotDto) {
    sendText(statusCode, fixThisJson.encodeToString(SnapshotDto.serializer(), value), "application/json; charset=utf-8")
}

internal fun HttpExchange.sendJson(statusCode: Int, value: FeedbackPreviewSnapshot) {
    sendText(statusCode, fixThisJson.encodeToString(FeedbackPreviewSnapshot.serializer(), value), "application/json; charset=utf-8")
}

internal fun HttpExchange.sendJson(statusCode: Int, value: AnnotationDto) {
    sendText(statusCode, fixThisJson.encodeToString(AnnotationDto.serializer(), value), "application/json; charset=utf-8")
}

internal fun HttpExchange.sendJson(statusCode: Int, value: FeedbackNavigationResult) {
    sendText(statusCode, fixThisJson.encodeToString(FeedbackNavigationResult.serializer(), value), "application/json; charset=utf-8")
}

internal fun HttpExchange.sendJson(statusCode: Int, value: ConsoleConnectionStatus) {
    sendText(statusCode, fixThisJson.encodeToString(ConsoleConnectionStatus.serializer(), value), "application/json; charset=utf-8")
}

internal fun HttpExchange.sendJson(statusCode: Int, value: JsonObject) {
    sendText(statusCode, fixThisJson.encodeToString(JsonObject.serializer(), value), "application/json; charset=utf-8")
}

internal fun HttpExchange.sendJson(statusCode: Int, value: AgentHandoffResponse) {
    val base = fixThisJson.encodeToJsonElement(AgentHandoffResponse.serializer(), value).jsonObject
    val sessionEl = base["session"]?.jsonObject
    val enriched = if (sessionEl == null) base else JsonObject(base + ("session" to enrichSessionJson(sessionEl)))
    sendText(statusCode, fixThisJson.encodeToString(JsonObject.serializer(), enriched), "application/json; charset=utf-8")
}

internal fun HttpExchange.sendJson(statusCode: Int, value: ConsoleDeviceList) {
    sendText(statusCode, fixThisJson.encodeToString(ConsoleDeviceList.serializer(), value), "application/json; charset=utf-8")
}

internal fun HttpExchange.sendMarkdown(statusCode: Int, text: String) {
    sendText(statusCode, text, "text/markdown; charset=utf-8")
}

internal fun HttpExchange.sendText(statusCode: Int, text: String, contentType: String) {
    sendBytes(statusCode, text.toByteArray(Charsets.UTF_8), contentType)
}

internal fun HttpExchange.sendBytes(statusCode: Int, bytes: ByteArray, contentType: String) {
    responseHeaders.set("Content-Type", contentType)
    sendResponseHeaders(statusCode, bytes.size.toLong())
    responseBody.use { output -> output.write(bytes) }
}

internal fun HttpExchange.sendNoContent() {
    sendResponseHeaders(204, -1)
    close()
}

internal fun HttpExchange.requestBodyText(): String = requestBody.use { input -> input.readBytes().toString(Charsets.UTF_8) }

internal fun <T> HttpExchange.decodeJsonBody(
    serializer: KSerializer<T>,
    body: String = requestBodyText(),
    blankValue: T? = null,
): T {
    if (body.isBlank() && blankValue != null) return blankValue
    return runCatching {
        fixThisJson.decodeFromString(serializer, body)
    }.getOrElse { error ->
        throw FeedbackConsoleHttpException(400, error.message ?: "Invalid JSON request body")
    }
}

internal class FeedbackConsoleHttpException(
    val statusCode: Int,
    override val message: String,
    val errorCode: String? = null,
    val action: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal fun etagOf(prefix: String, version: Long): String = "\"$prefix:$version\""

internal fun HttpExchange.ifNoneMatch(): String? = requestHeaders.getFirst("If-None-Match")

internal fun HttpExchange.sendNotModified(etag: String) {
    responseHeaders.set("ETag", etag)
    sendResponseHeaders(304, -1)
    close()
}

internal fun HttpExchange.sendJsonWithEtag(
    statusCode: Int,
    etag: String,
    write: () -> Unit,
) {
    responseHeaders.set("ETag", etag)
    write()
}
