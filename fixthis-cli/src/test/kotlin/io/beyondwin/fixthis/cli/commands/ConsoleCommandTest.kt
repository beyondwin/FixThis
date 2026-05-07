package io.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleCommandTest {
    @Test
    fun buildsMcpConsoleCommand() {
        val executable = java.io.File("/tmp/fixthis-mcp")
        val command = buildConsoleProcessCommand(
            executable = executable,
            packageName = "io.beyondwin.fixthis.sample",
            projectDir = "/repo",
            consoleAssetsDir = "/repo/fixthis-mcp/src/main/resources/console",
        )

        assertEquals(
            listOf(
                "/tmp/fixthis-mcp",
                "--console",
                "--package",
                "io.beyondwin.fixthis.sample",
                "--project-dir",
                "/repo",
                "--console-assets-dir",
                "/repo/fixthis-mcp/src/main/resources/console",
            ),
            command,
        )
    }

    @Test
    fun buildsMcpConsoleCommandWithoutPackageOverride() {
        val command = buildConsoleProcessCommand(
            executable = java.io.File("/tmp/fixthis-mcp"),
            packageName = null,
            projectDir = "/repo",
            consoleAssetsDir = null,
        )

        assertEquals(
            listOf(
                "/tmp/fixthis-mcp",
                "--console",
                "--project-dir",
                "/repo",
            ),
            command,
        )
    }
}
