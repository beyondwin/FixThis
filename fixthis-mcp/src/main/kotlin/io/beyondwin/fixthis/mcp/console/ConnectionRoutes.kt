package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import kotlinx.coroutines.runBlocking

internal class ConnectionRoutes(private val service: FeedbackSessionService) : ConsoleRoute {
    override fun matches(path: String): Boolean = path == "/api/connection" ||
        path == "/api/app/launch" ||
        path == "/api/heartbeat"

    override fun handle(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/api/connection" -> exchange.requireMethod("GET") {
                exchange.sendJson(200, runBlocking { service.connectionStatus() })
            }
            "/api/app/launch" -> exchange.requireMethod("POST") {
                exchange.sendJson(200, runBlocking { service.launchAppForCurrentSession() })
            }
            "/api/heartbeat" -> exchange.requireMethod("GET") {
                exchange.sendJson(200, runBlocking { service.heartbeatForCurrentSession() })
            }
        }
    }
}
