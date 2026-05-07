package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange

internal interface ConsoleRoute {
    fun matches(path: String): Boolean
    fun handle(exchange: HttpExchange)
}

internal class ConsoleRouteTable(private val routes: List<ConsoleRoute>) {
    fun handle(exchange: HttpExchange): Boolean {
        val route = routes.firstOrNull { it.matches(exchange.requestURI.path) } ?: return false
        route.handle(exchange)
        return true
    }
}
