package io.github.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackQueueFormatter
import kotlinx.serialization.Serializable
import java.io.File

private const val SESSION_ID_CAPTURE_GROUP = 1
private const val HTTP_STATUS_NOT_FOUND = 404

internal class SessionRoutes(
    private val service: FeedbackSessionService,
    private val consoleAssetsDir: File?,
    private val consoleToken: String,
    private val eventBus: ConsoleEventBus,
    private val packagedIndexHtml: String? = null,
) : ConsoleRoute {
    override fun matches(path: String): Boolean = path == "/" ||
        path == "/favicon.ico" ||
        path == "/api/session" ||
        path == "/api/sessions" ||
        path == "/api/session/open" ||
        path == "/api/session/close" ||
        runtimeEvidencePolicyPath.matches(path) ||
        path == "/api/export/markdown"

    override fun handle(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/" -> exchange.handleIndex()
            "/favicon.ico" -> exchange.requireMethod("GET") {
                exchange.sendNoContent()
            }
            "/api/session" -> exchange.requireMethod("GET") {
                val current = service.currentSessionOrNull()
                if (current == null) {
                    exchange.sendText(200, "null", "application/json; charset=utf-8")
                } else {
                    val availabilityHash = current.runtimeEvidence.hashCode().toUInt().toString(HEX_RADIX)
                    val etag = etagOf("${current.sessionId}:$availabilityHash", current.updatedAtEpochMillis)
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
                val session = service.openSession(request.packageName, request.sessionId, request.newSession)
                eventBus.emitSessionUpdated(session)
                exchange.sendJson(200, session)
            }
            "/api/session/close" -> exchange.requireMethod("POST") {
                val request = exchange.decodeOpenSessionBody()
                val sessionId = request.sessionId ?: service.requireCurrentSession().sessionId
                val session = service.closeSession(sessionId)
                eventBus.emitSessionUpdated(session)
                exchange.sendJson(200, session)
            }
            "/api/export/markdown" -> exchange.requireMethod("GET") {
                exchange.sendText(200, FeedbackQueueFormatter.toMarkdown(service.requireCurrentSession()), "text/markdown; charset=utf-8")
            }
            else -> exchange.handleRuntimeEvidencePolicy()
        }
    }

    private fun HttpExchange.handleIndex() {
        if (requestMethod == "HEAD") {
            sendResponseHeaders(200, -1)
            close()
            return
        }
        requireMethod("GET") {
            val html = packagedIndexHtml ?: FeedbackConsoleAssets.html(consoleAssetsDir, consoleToken)
            sendText(200, html, "text/html; charset=utf-8")
        }
    }

    private fun HttpExchange.decodeOpenSessionBody(): OpenSessionRequest = decodeJsonBody(OpenSessionRequest.serializer())

    private fun HttpExchange.handleRuntimeEvidencePolicy() {
        requireMethod("POST") {
            val sessionId = runtimeEvidencePolicyPath.matchEntire(requestURI.path)
                ?.groupValues
                ?.get(SESSION_ID_CAPTURE_GROUP)
                ?.takeIf { it.isNotBlank() }
                ?: throw FeedbackConsoleHttpException(HTTP_STATUS_NOT_FOUND, "Feedback session not found")
            val request = decodeJsonBody(UpdateRuntimeEvidencePolicyRequest.serializer())
            val session = service.updateRuntimeEvidencePolicy(sessionId, request.policy)
            eventBus.emitSessionUpdated(session)
            sendJson(200, session)
        }
    }

    @Serializable
    private data class OpenSessionRequest(
        val packageName: String? = null,
        val sessionId: String? = null,
        val newSession: Boolean = false,
    )
}

private const val HEX_RADIX = 16
private val runtimeEvidencePolicyPath = Regex("^/api/sessions/([^/]+)/runtime-evidence-policy$")
