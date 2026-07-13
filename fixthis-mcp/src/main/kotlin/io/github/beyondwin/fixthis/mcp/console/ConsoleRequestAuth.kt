@file:Suppress("MaxLineLength")

package io.github.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val CONSOLE_TOKEN_HEADER = "X-FixThis-Console-Token"
internal const val CONSOLE_TOKEN_QUERY = "consoleToken"

private const val FORBIDDEN_STATUS = 403

internal data class ConsoleRequestAuthConfig(
    val token: String,
    val host: String,
    val port: Int,
)

private val ConsoleMutatingMethods = setOf("POST", "PUT", "PATCH", "DELETE")

internal fun HttpExchange.requiresConsoleApiGuard(): Boolean = requestURI.path.startsWith("/api/") && requestURI.path != "/api/server-version"

internal fun HttpExchange.requireConsoleApiAllowed(config: ConsoleRequestAuthConfig) {
    val failure = consoleApiAuthFailure(config)
    if (failure != null) {
        throw FeedbackConsoleHttpException(FORBIDDEN_STATUS, failure)
    }
}

private fun HttpExchange.consoleApiAuthFailure(config: ConsoleRequestAuthConfig): String? {
    val origin = requestHeaders.getFirst("Origin")
    val host = requestHeaders.getFirst("Host")
    val mutating = requestMethod.uppercase() in ConsoleMutatingMethods
    val suppliedToken = requestHeaders.getFirst(CONSOLE_TOKEN_HEADER)
        ?: if (!mutating) queryParameter(CONSOLE_TOKEN_QUERY) else null
    return when {
        mutating && origin != null && !origin.isAllowedConsoleOrigin(config) -> "Forbidden origin"
        host != null && !host.isAllowedConsoleHost(config) -> "Forbidden host"
        !constantTimeEquals(config.token, suppliedToken) -> "Missing console token"
        else -> null
    }
}

private fun String.isAllowedConsoleOrigin(config: ConsoleRequestAuthConfig): Boolean = this == "http://127.0.0.1:${config.port}" ||
    this == "http://localhost:${config.port}" ||
    this == "http://[::1]:${config.port}" ||
    this == "http://${config.host.toUrlHost()}:${config.port}"

private fun String.isAllowedConsoleHost(config: ConsoleRequestAuthConfig): Boolean = this == "127.0.0.1:${config.port}" ||
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
