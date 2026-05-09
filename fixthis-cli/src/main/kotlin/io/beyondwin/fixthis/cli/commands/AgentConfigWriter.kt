package io.beyondwin.fixthis.cli.commands

import java.io.File

internal interface AgentConfigWriter {
    val name: String
    val scope: String
    fun configFile(projectRoot: File, userHome: File = File(System.getProperty("user.home"))): File
    fun merge(current: String?, entry: McpConfigEntry): String
}
