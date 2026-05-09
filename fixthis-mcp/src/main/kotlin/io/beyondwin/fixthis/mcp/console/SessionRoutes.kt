package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.beyondwin.fixthis.mcp.session.FeedbackQueueFormatter
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import java.io.File
import kotlinx.serialization.Serializable

internal class SessionRoutes(
    private val service: FeedbackSessionService,
    private val consoleAssetsDir: File?,
    private val consoleToken: String,
) : ConsoleRoute {
    override fun matches(path: String): Boolean =
        path == "/" ||
            path == "/favicon.ico" ||
            path == "/api/session" ||
            path == "/api/sessions" ||
            path == "/api/session/open" ||
            path == "/api/session/close" ||
            path == "/api/export/markdown"

    override fun handle(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/" -> exchange.requireMethod("GET") {
                exchange.sendText(200, FeedbackConsoleAssets.html(consoleAssetsDir, consoleToken), "text/html; charset=utf-8")
            }
            "/favicon.ico" -> exchange.requireMethod("GET") {
                exchange.sendNoContent()
            }
            "/api/session" -> exchange.requireMethod("GET") {
                val current = service.currentSessionOrNull()
                if (current == null) {
                    exchange.sendText(200, "null", "application/json; charset=utf-8")
                } else {
                    val etag = etagOf(current.sessionId, current.updatedAtEpochMillis)
                    if (exchange.ifNoneMatch() == etag) {
                        exchange.sendNotModified(etag)
                    } else {
                        exchange.responseHeaders.set("ETag", etag)
                        exchange.sendJson(200, current)
                    }
                }
            }
            "/api/sessions" -> exchange.requireMethod("GET") {
                val list = service.listSessions(
                    packageNameOverride = exchange.queryParameter("packageName"),
                    includeClosed = exchange.queryBoolean("includeClosed"),
                )
                val maxUpdated = list.sessions.maxOfOrNull { it.updatedAtEpochMillis } ?: 0L
                val etag = etagOf("${list.sessions.size}", maxUpdated)
                if (exchange.ifNoneMatch() == etag) {
                    exchange.sendNotModified(etag)
                } else {
                    exchange.responseHeaders.set("ETag", etag)
                    exchange.sendJson(200, list)
                }
            }
            "/api/session/open" -> exchange.requireMethod("POST") {
                val request = exchange.decodeOpenSessionBody()
                exchange.sendJson(200, service.openSession(request.packageName, request.sessionId, request.newSession))
            }
            "/api/session/close" -> exchange.requireMethod("POST") {
                val request = exchange.decodeOpenSessionBody()
                val sessionId = request.sessionId ?: service.requireCurrentSession().sessionId
                exchange.sendJson(200, service.closeSession(sessionId))
            }
            "/api/export/markdown" -> exchange.requireMethod("GET") {
                exchange.sendText(200, FeedbackQueueFormatter.toMarkdown(service.requireCurrentSession()), "text/markdown; charset=utf-8")
            }
        }
    }

    private fun HttpExchange.decodeOpenSessionBody(): OpenSessionRequest =
        decodeJsonBody(OpenSessionRequest.serializer())

    @Serializable
    private data class OpenSessionRequest(
        val packageName: String? = null,
        val sessionId: String? = null,
        val newSession: Boolean = false,
    )
}
