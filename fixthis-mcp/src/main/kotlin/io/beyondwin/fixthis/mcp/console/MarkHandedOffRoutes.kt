package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.beyondwin.fixthis.mcp.session.FeedbackSessionException
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService

internal class MarkHandedOffRoutes(
    private val service: FeedbackSessionService,
    private val eventBus: ConsoleEventBus,
) : ConsoleRoute {
    private val pathPrefix = "/api/sessions/"
    private val pathSuffix = "/items/mark-handed-off"

    override fun matches(path: String): Boolean = path.startsWith(pathPrefix) &&
        path.endsWith(pathSuffix) &&
        path.length > pathPrefix.length + pathSuffix.length

    override fun handle(exchange: HttpExchange) {
        val raw = exchange.requestURI.path
        if (!matches(raw)) return
        val sessionId = raw.removePrefix(pathPrefix).removeSuffix(pathSuffix)
        if (sessionId.isBlank()) throw FeedbackConsoleHttpException(404, "session not found")

        exchange.requireMethod("POST") {
            val body = exchange.decodeJsonBody(
                MarkHandedOffRequest.serializer(),
                blankValue = MarkHandedOffRequest(),
            )
            if (body.itemIds.isEmpty()) {
                throw FeedbackConsoleHttpException(400, "itemIds must not be empty")
            }
            val session = try {
                service.markItemsHandedOff(sessionId, body.itemIds)
            } catch (error: FeedbackSessionException) {
                throw FeedbackConsoleHttpException(404, "session not found")
            }
            eventBus.emitSessionUpdated(session)
            exchange.sendJson(200, session)
        }
    }
}
