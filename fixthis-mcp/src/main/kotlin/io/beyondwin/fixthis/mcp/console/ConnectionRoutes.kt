package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class ConnectionRoutes(
    private val service: FeedbackSessionService,
    private val eventBus: ConsoleEventBus,
) : ConsoleRoute {
    override fun matches(path: String): Boolean = path == "/api/connection" ||
        path == "/api/app/launch" ||
        path == "/api/heartbeat"

    override fun handle(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/api/connection" -> exchange.requireMethod("GET") {
                val status = runBlocking { service.connectionStatus() }
                eventBus.emitConnectionUpdated(status)
                exchange.sendJson(200, status)
            }
            "/api/app/launch" -> exchange.requireMethod("POST") {
                val status = runBlocking { service.launchAppForCurrentSession() }
                eventBus.emitConnectionUpdated(status)
                exchange.sendJson(200, status)
            }
            "/api/heartbeat" -> exchange.requireMethod("GET") {
                val status = runBlocking { service.heartbeatForCurrentSession() }
                eventBus.emit(
                    "heartbeat",
                    buildJsonObject {
                        put("heartbeat", status)
                    },
                )
                exchange.sendJson(200, status)
            }
        }
    }
}

private fun ConsoleEventBus.emitConnectionUpdated(status: ConsoleConnectionStatus) {
    emit(
        "connection-updated",
        buildJsonObject {
            put("connection", fixThisJson.encodeToJsonElement(ConsoleConnectionStatus.serializer(), status).jsonObject)
        },
    )
}
