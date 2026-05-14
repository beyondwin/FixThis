package io.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import io.beyondwin.fixthis.cli.DiagnosticContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class SetupCommandTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rejectsInvalidServerNameBeforeWriting() {
        val projectRoot = temporaryFolder.newFolder("project")
        val userHome = temporaryFolder.newFolder("home")
        val codexConfig = userHome.resolve(".codex/config.toml")
        codexConfig.parentFile.mkdirs()
        codexConfig.writeText("[mcp_servers.playwright]\ncommand = \"npx\"\n")

        val error = withUserHome(userHome) {
            assertThrows(CliktError::class.java) {
                SetupCommand().parse(
                    listOf(
                        "--package",
                        "io.beyondwin.fixthis.sample",
                        "--project-dir",
                        projectRoot.absolutePath,
                        "--write",
                        "--target",
                        "codex",
                        "--server-name",
                        "bad name",
                    ),
                )
            }
        }

        assertEquals("[mcp_servers.playwright]\ncommand = \"npx\"\n", codexConfig.readText())
        assertEquals("Invalid MCP server name `bad name`; use only letters, numbers, underscores, and hyphens.", error.message)
    }

    @Test
    fun targetAllPreflightsMergesBeforeWritingAnyConfig() {
        val projectRoot = temporaryFolder.newFolder("project").canonicalFile
        val userHome = temporaryFolder.newFolder("home")
        val codexConfig = userHome.resolve(".codex/config.toml")
        val claudeSettings = projectRoot.resolve(".claude/settings.json")
        codexConfig.parentFile.mkdirs()
        claudeSettings.parentFile.mkdirs()
        val originalCodex = "[mcp_servers.fixthis]\ncommand = \"old\"\n"
        codexConfig.writeText(originalCodex)
        claudeSettings.writeText("""{"mcpServers":""")

        val error = withUserHome(userHome) {
            assertThrows(CliktError::class.java) {
                SetupCommand().parse(
                    listOf(
                        "--package",
                        "io.beyondwin.fixthis.sample",
                        "--project-dir",
                        projectRoot.absolutePath,
                        "--write",
                        "--target",
                        "all",
                    ),
                )
            }
        }

        assertEquals(originalCodex, codexConfig.readText())
        assertTrue(
            "Expected message to start with merge error prefix",
            error.message!!.startsWith("Could not merge claude MCP config at ${claudeSettings.absolutePath}."),
        )
    }

    @Test
    fun atomicWriterLeavesExistingConfigWhenMoveFails() {
        val configFile = temporaryFolder.newFolder("home").resolve(".codex/config.toml")
        configFile.parentFile.mkdirs()
        configFile.writeText("original")

        assertThrows(IOException::class.java) {
            AtomicConfigFileWriter.write(configFile, "replacement") { _, _, _ ->
                throw IOException("move failed")
            }
        }

        assertEquals("original", configFile.readText())
        assertFalse(
            Files.list(configFile.parentFile.toPath()).use { paths ->
                paths.anyMatch { it.fileName.toString().startsWith(".config.toml") }
            },
        )
    }

    @Test
    fun atomicWriterForcesTempFileBeforeMoveAndParentDirectoryAfterMove() {
        val configFile = temporaryFolder.newFolder("home").resolve(".codex/config.toml")
        configFile.parentFile.mkdirs()
        val events = mutableListOf<String>()

        AtomicConfigFileWriter.write(
            file = configFile,
            content = "replacement",
            move = { source, target, _ ->
                assertEquals(listOf("force-temp"), events)
                events += "move"
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            },
            forceFile = { path ->
                assertTempConfigPath(configFile.parentFile.toPath(), path)
                events += "force-temp"
            },
            forceDirectory = { path ->
                assertEquals(listOf("force-temp", "move"), events)
                assertEquals(configFile.parentFile.toPath(), path)
                events += "force-parent"
            },
        )

        assertEquals("replacement", configFile.readText())
        assertEquals(listOf("force-temp", "move", "force-parent"), events)
    }

    @Test
    fun atomicWriterTreatsUnsupportedParentDirectoryForceAsBestEffortAfterMove() {
        val configFile = temporaryFolder.newFolder("home").resolve(".codex/config.toml")
        configFile.parentFile.mkdirs()

        AtomicConfigFileWriter.write(
            file = configFile,
            content = "replacement",
            forceDirectory = {
                throw UnsupportedOperationException("directory force unsupported")
            },
        )

        assertEquals("replacement", configFile.readText())
    }

    private fun <T> withUserHome(userHome: java.io.File, block: () -> T): T {
        val original = System.getProperty("user.home")
        System.setProperty("user.home", userHome.absolutePath)
        return try {
            block()
        } finally {
            if (original == null) {
                System.clearProperty("user.home")
            } else {
                System.setProperty("user.home", original)
            }
        }
    }

    @Test
    fun mergeErrorIncludesCauseMessageForDebugging() {
        val projectRoot = temporaryFolder.newFolder("project").canonicalFile
        val userHome = temporaryFolder.newFolder("home")
        val claudeSettings = projectRoot.resolve(".claude/settings.json")
        claudeSettings.parentFile.mkdirs()
        claudeSettings.writeText("""{"mcpServers":""") // truncated JSON

        val error = withUserHome(userHome) {
            assertThrows(CliktError::class.java) {
                SetupCommand().parse(
                    listOf(
                        "--package",
                        "io.beyondwin.fixthis.sample",
                        "--project-dir",
                        projectRoot.absolutePath,
                        "--write",
                        "--target",
                        "claude",
                    ),
                )
            }
        }

        assertTrue(
            "Expected message to contain file path",
            error.message!!.contains(claudeSettings.absolutePath),
        )
        assertTrue(
            "Expected message to contain cause detail",
            error.message!!.length > "Could not merge claude MCP config at ${claudeSettings.absolutePath}:".length,
        )
        assertNotNull("Expected cause to be set", error.cause)
    }

    private fun assertTempConfigPath(parent: Path, path: Path) {
        assertEquals(parent, path.parent)
        assertTrue(path.fileName.toString().startsWith(".config.toml"))
    }

    @Test
    fun mergeErrorIncludesCategoryAndFixForMalformedJson() {
        val projectRoot = temporaryFolder.newFolder("project").canonicalFile
        val userHome = temporaryFolder.newFolder("home")
        val claudeSettings = projectRoot.resolve(".claude/settings.json")
        claudeSettings.parentFile.mkdirs()
        claudeSettings.writeText("""{"mcpServers":""") // truncated JSON

        val error = withUserHome(userHome) {
            assertThrows(CliktError::class.java) {
                SetupCommand().parse(
                    listOf(
                        "--package",
                        "io.beyondwin.fixthis.sample",
                        "--project-dir",
                        projectRoot.absolutePath,
                        "--write",
                        "--target",
                        "claude",
                    ),
                )
            }
        }

        val message = error.message!!
        assertTrue(
            "Expected Category line, got: $message",
            message.contains("Category: MALFORMED_JSON"),
        )
        assertTrue("Expected Fix line", message.contains("Fix:"))
        assertNotNull("Expected cause attached", error.cause)
    }

    @Test
    fun verboseFlagSetsDiagnosticContext() {
        val projectRoot = temporaryFolder.newFolder("project").canonicalFile
        val userHome = temporaryFolder.newFolder("home")
        val claudeSettings = projectRoot.resolve(".claude/settings.json")
        claudeSettings.parentFile.mkdirs()
        claudeSettings.writeText("""{"mcpServers":""")

        DiagnosticContext.reset()
        withUserHome(userHome) {
            runCatching {
                SetupCommand().parse(
                    listOf(
                        "--package",
                        "io.beyondwin.fixthis.sample",
                        "--project-dir",
                        projectRoot.absolutePath,
                        "--write",
                        "--target",
                        "claude",
                        "--verbose",
                    ),
                )
            }
        }
        assertTrue("Expected DiagnosticContext.verbose = true after --verbose run", DiagnosticContext.verbose)
        DiagnosticContext.reset()
    }
}
