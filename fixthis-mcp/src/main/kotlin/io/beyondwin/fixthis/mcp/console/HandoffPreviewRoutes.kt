package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.beyondwin.fixthis.mcp.session.CompactHandoffRenderer
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import kotlinx.coroutines.runBlocking

internal class HandoffPreviewRoutes(private val service: FeedbackSessionService) : ConsoleRoute {
    private val pathPrefix = "/api/sessions/"
    private val pathSuffix = "/handoff-preview"

    override fun matches(path: String): Boolean = path.startsWith(pathPrefix) &&
        path.endsWith(pathSuffix) &&
        path.length > pathPrefix.length + pathSuffix.length

    override fun handle(exchange: HttpExchange) {
        val raw = exchange.requestURI.path
        if (!matches(raw)) return
        val sessionId = raw.removePrefix(pathPrefix).removeSuffix(pathSuffix)
        if (sessionId.isBlank()) throw FeedbackConsoleHttpException(404, "session not found")

        exchange.requireMethod("POST") {
            val body = exchange.decodeJsonBody(HandoffPreviewRequest.serializer(), blankValue = HandoffPreviewRequest())
            if (body.itemIds.isEmpty()) {
                throw FeedbackConsoleHttpException(400, "itemIds must not be empty")
            }
            val session = service.findSession(sessionId)
                ?: throw FeedbackConsoleHttpException(404, "session not found")
            val refreshed = runBlocking { service.refreshSourceEvidenceForHandoff(session) }
            val markdown = CompactHandoffRenderer.render(refreshed, itemIds = body.itemIds)
            exchange.sendMarkdown(200, markdown)
        }
    }
}
