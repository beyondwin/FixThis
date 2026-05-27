package io.github.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBusStats

internal class ConsoleEventDiagnosticsRoutes(
    private val eventBus: ConsoleEventBus,
) : ConsoleRoute {
    override fun matches(path: String): Boolean = path == "/api/events/diagnostics"

    override fun handle(exchange: HttpExchange) {
        exchange.requireMethod("GET") {
            exchange.sendText(
                statusCode = 200,
                text = fixThisJson.encodeToString(ConsoleEventBusStats.serializer(), eventBus.stats()),
                contentType = "application/json; charset=utf-8",
            )
        }
    }
}
