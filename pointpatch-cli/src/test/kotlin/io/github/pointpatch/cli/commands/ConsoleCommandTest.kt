package io.github.pointpatch.cli.commands

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleCommandTest {
    @Test
    fun buildsMcpConsoleCommand() {
        val executable = java.io.File("/tmp/pointpatch-mcp")
        val command = buildConsoleProcessCommand(
            executable = executable,
            packageName = "io.github.pointpatch.sample",
            projectDir = "/repo",
        )

        assertEquals(
            listOf(
                "/tmp/pointpatch-mcp",
                "--console",
                "--package",
                "io.github.pointpatch.sample",
                "--project-dir",
                "/repo",
            ),
            command,
        )
    }

    @Test
    fun buildsMcpConsoleCommandWithoutPackageOverride() {
        val command = buildConsoleProcessCommand(
            executable = java.io.File("/tmp/pointpatch-mcp"),
            packageName = null,
            projectDir = "/repo",
        )

        assertEquals(
            listOf(
                "/tmp/pointpatch-mcp",
                "--console",
                "--project-dir",
                "/repo",
            ),
            command,
        )
    }
}
