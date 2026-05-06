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
        )

        assertEquals(
            listOf(
                "/tmp/fixthis-mcp",
                "--console",
                "--package",
                "io.beyondwin.fixthis.sample",
                "--project-dir",
                "/repo",
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
