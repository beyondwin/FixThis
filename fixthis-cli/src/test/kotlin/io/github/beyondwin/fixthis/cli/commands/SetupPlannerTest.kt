package io.github.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SetupPlannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun targetCodexSelectsOnlyCodexWriter() {
        assertEquals(listOf("codex"), SetupPlanner.selectedWriters("codex").map { it.name })
    }

    @Test
    fun targetClaudeSelectsOnlyClaudeWriter() {
        assertEquals(listOf("claude"), SetupPlanner.selectedWriters("claude").map { it.name })
    }

    @Test
    fun targetAllSelectsBothWriters() {
        assertEquals(listOf("codex", "claude"), SetupPlanner.selectedWriters("all").map { it.name })
    }

    @Test
    fun mcpConfigEntryFallsBackToFixthisMcpWhenExecutableIsMissing() {
        val projectRoot = temporaryFolder.newFolder("project").canonicalFile

        val entry = buildMcpConfigEntry(
            resolvedPackage = "io.github.beyondwin.fixthis.sample",
            root = projectRoot,
            serverName = "fixthis",
            sdk = null,
            executable = null,
        )

        assertEquals("fixthis", entry.command)
        assertEquals(
            listOf(
                "mcp",
                "--package",
                "io.github.beyondwin.fixthis.sample",
                "--project-dir",
                projectRoot.absolutePath,
            ),
            entry.args,
        )
    }

    @Test
    fun mcpConfigEntryAvoidsPersistingVersionedHomebrewCellarPath() {
        val projectRoot = temporaryFolder.newFolder("project").canonicalFile
        val executable = temporaryFolder.root
            .resolve("Cellar/fixthis/0.6.0/libexec/fixthis-mcp/bin/fixthis-mcp")
        executable.parentFile.mkdirs()
        executable.writeText("#!/bin/sh\n")
        executable.setExecutable(true)

        val entry = buildMcpConfigEntry(
            resolvedPackage = "io.github.beyondwin.fixthis.sample",
            root = projectRoot,
            serverName = "fixthis",
            sdk = null,
            executable = executable,
        )

        assertEquals("fixthis", entry.command)
        assertEquals(
            listOf(
                "mcp",
                "--package",
                "io.github.beyondwin.fixthis.sample",
                "--project-dir",
                projectRoot.absolutePath,
            ),
            entry.args,
        )
    }

    @Test
    fun mcpConfigEntryUsesStableHomebrewBinPathWhenAvailable() {
        val homebrewRoot = temporaryFolder.newFolder("homebrew").canonicalFile
        val projectRoot = temporaryFolder.newFolder("project").canonicalFile
        val cellarExecutable = homebrewRoot
            .resolve("Cellar/fixthis/0.6.0/libexec/fixthis-mcp/bin/fixthis-mcp")
        cellarExecutable.parentFile.mkdirs()
        cellarExecutable.writeText("#!/bin/sh\n")
        cellarExecutable.setExecutable(true)
        val stableExecutable = homebrewRoot.resolve("bin/fixthis-mcp")
        stableExecutable.parentFile.mkdirs()
        stableExecutable.writeText("#!/bin/sh\n")
        stableExecutable.setExecutable(true)

        val entry = buildMcpConfigEntry(
            resolvedPackage = "io.github.beyondwin.fixthis.sample",
            root = projectRoot,
            serverName = "fixthis",
            sdk = null,
            executable = cellarExecutable,
        )

        assertEquals(stableExecutable.absolutePath, entry.command)
        assertEquals(
            listOf("--package", "io.github.beyondwin.fixthis.sample", "--project-dir", projectRoot.absolutePath),
            entry.args,
        )
    }

    @Test
    fun buildWritePlansPreservesDryRunMetadataWithoutWritingConfigFiles() {
        val projectRoot = temporaryFolder.newFolder("project").canonicalFile
        val configFile = projectRoot.resolve("fake-agent.conf")
        val writer = RecordingWriter(
            name = "fake-agent",
            scope = "project-local",
            configFile = configFile,
            mergedContent = "merged-content",
        )

        val plan = SetupPlanner.buildWritePlans(
            writers = listOf(writer),
            projectRoot = projectRoot,
            entry = mcpEntry(),
        ).single()

        assertEquals("fake-agent", plan.writerName)
        assertEquals("project-local", plan.scope)
        assertEquals(configFile, plan.configFile)
        assertEquals("merged-content", plan.content)
        assertEquals(null, writer.observedCurrent)
        assertFalse("planner must not create config files", configFile.exists())
    }

    private fun mcpEntry(): McpConfigEntry = McpConfigEntry(
        serverName = "fixthis",
        command = "fixthis",
        args = listOf("mcp"),
    )

    private class RecordingWriter(
        override val name: String,
        override val scope: String,
        private val configFile: File,
        private val mergedContent: String,
    ) : AgentConfigWriter {
        var observedCurrent: String? = "unset"

        override fun configFile(projectRoot: File, userHome: File): File = configFile

        override fun merge(current: String?, entry: McpConfigEntry): String {
            observedCurrent = current
            return mergedContent
        }
    }
}
