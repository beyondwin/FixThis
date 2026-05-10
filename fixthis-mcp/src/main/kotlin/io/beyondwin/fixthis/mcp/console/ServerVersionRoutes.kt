package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.beyondwin.fixthis.mcp.BuildInfo

// Mirrors the protocol version constant defined in BridgeProtocol.VERSION (fixthis-compose-sidekick)
// and BridgeClient (fixthis-cli). Keep in sync if the protocol version ever changes.
private const val BridgeProtocolVersion = "1.1"

internal class ServerVersionRoutes : ConsoleRoute {
    override fun matches(path: String): Boolean = path == "/api/server-version"

    override fun handle(exchange: HttpExchange) {
        exchange.requireMethod("GET") {
            val payload = """{"serverBuildEpochMs":${BuildInfo.BUILD_EPOCH_MS},"serverGitSha":"${BuildInfo.GIT_SHA}","bridgeProtocolVersion":"$BridgeProtocolVersion"}"""
            val bytes = payload.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
