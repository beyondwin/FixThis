package io.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError

private val McpServerNamePattern = Regex("[A-Za-z0-9_-]+")

internal data class McpConfigEntry(
    val serverName: String,
    val command: String,
    val args: List<String>,
    val env: Map<String, String> = emptyMap(),
) {
    init {
        require(isValidMcpServerName(serverName)) {
            "Invalid MCP server name `$serverName`; use only letters, numbers, underscores, and hyphens."
        }
    }
}

internal fun validateMcpServerName(serverName: String): String {
    if (!isValidMcpServerName(serverName)) {
        throw CliktError(
            "Invalid MCP server name `$serverName`; use only letters, numbers, underscores, and hyphens.",
        )
    }
    return serverName
}

private fun isValidMcpServerName(serverName: String): Boolean =
    McpServerNamePattern.matches(serverName)
