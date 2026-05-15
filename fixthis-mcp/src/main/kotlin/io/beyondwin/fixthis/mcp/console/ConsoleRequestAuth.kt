package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val ConsoleTokenHeader = "X-FixThis-Console-Token"

internal data class ConsoleRequestAuthConfig(
    val token: String,
    val host: String,
    val port: Int,
)

private val ConsoleMutatingMethods = setOf("POST", "PUT", "PATCH", "DELETE")

internal fun HttpExchange.requiresConsoleMutationGuard(): Boolean =
    requestURI.path.startsWith("/api/") && requestMethod.uppercase() in ConsoleMutatingMethods

internal fun HttpExchange.requireConsoleMutationAllowed(config: ConsoleRequestAuthConfig) {
    val origin = requestHeaders.getFirst("Origin")
    if (origin != null && !origin.isAllowedConsoleOrigin(config)) {
        throw FeedbackConsoleHttpException(403, "Forbidden origin")
    }
    val host = requestHeaders.getFirst("Host")
    if (host != null && !host.isAllowedConsoleHost(config)) {
        throw FeedbackConsoleHttpException(403, "Forbidden host")
    }
    if (!constantTimeEquals(config.token, requestHeaders.getFirst(ConsoleTokenHeader))) {
        throw FeedbackConsoleHttpException(403, "Missing console token")
    }
}

private fun String.isAllowedConsoleOrigin(config: ConsoleRequestAuthConfig): Boolean =
    this == "http://127.0.0.1:${config.port}" ||
        this == "http://localhost:${config.port}" ||
        this == "http://[::1]:${config.port}" ||
        this == "http://${config.host.toUrlHost()}:${config.port}"

private fun String.isAllowedConsoleHost(config: ConsoleRequestAuthConfig): Boolean =
    this == "127.0.0.1:${config.port}" ||
        this == "localhost:${config.port}" ||
        this == "[::1]:${config.port}" ||
        this == "${config.host.toUrlHost()}:${config.port}"

private fun constantTimeEquals(expected: String, supplied: String?): Boolean {
    if (supplied == null) return false
    return MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8),
        supplied.toByteArray(StandardCharsets.UTF_8),
    )
}

internal fun String.toUrlHost(): String = if (contains(':') && !startsWith("[")) "[$this]" else this
