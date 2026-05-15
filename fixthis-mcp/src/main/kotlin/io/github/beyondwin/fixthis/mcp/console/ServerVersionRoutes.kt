package io.github.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.github.beyondwin.fixthis.mcp.BuildInfo

// Mirrors the protocol version constant defined in BridgeProtocol.VERSION (fixthis-compose-sidekick)
// and BridgeClient (fixthis-cli). Keep in sync if the protocol version ever changes.
private const val BridgeProtocolVersion = "1.3"

internal class ServerVersionRoutes : ConsoleRoute {
    override fun matches(path: String): Boolean = path == "/api/server-version"

    override fun handle(exchange: HttpExchange) {
        exchange.requireMethod("GET") {
            val payload = """{"serverBuildEpochMs":${BuildInfo.BUILD_EPOCH_MS},"serverGitSha":"${BuildInfo.GIT_SHA}","bridgeProtocolVersion":"$BridgeProtocolVersion"}"""
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendText(200, payload, "application/json; charset=utf-8")
        }
    }
}
