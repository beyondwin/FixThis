package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import io.github.beyondwin.fixthis.cli.DiagnosticContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.CopyOption
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
                        "io.github.beyondwin.fixthis.sample",
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
                        "io.github.beyondwin.fixthis.sample",
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
                        "io.github.beyondwin.fixthis.sample",
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
                        "io.github.beyondwin.fixthis.sample",
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
    fun setupDryRunDoesNotLeakUnchangedClaudeMcpServers() {
        // Drives the full SetupCommand → applyWritePlan(dryRun=true) path via Clikt parse so
        // future regressions in the wire-up (e.g., dry-run bypassing applyWritePlan) are caught.
        val marker = "DO-NOT-LEAK-MARKER-XYZ"
        val projectRoot = temporaryFolder.newFolder("project")
        val claudeSettings = projectRoot.resolve(".claude/settings.json").apply {
            parentFile.mkdirs()
            writeText("""{"mcpServers":{"other":{"command":"$marker","args":[]}}}""")
        }
        val userHome = temporaryFolder.newFolder("home")

        val captured = captureStdout {
            withUserHome(userHome) {
                SetupCommand().parse(
                    listOf(
                        "--package", "io.github.beyondwin.fixthis.sample",
                        "--project-dir", projectRoot.absolutePath,
                        "--write",
                        "--target", "claude",
                        "--dry-run",
                    ),
                )
            }
        }

        assertFalse(
            "unchanged 'other' entry marker must not leak via dry-run output. Got: $captured",
            captured.contains(marker),
        )
        assertTrue(
            "added fixthis entry should appear in diff. Got: $captured",
            captured.contains("fixthis"),
        )
        assertEquals(
            "dry-run must not modify the on-disk file",
            """{"mcpServers":{"other":{"command":"$marker","args":[]}}}""",
            claudeSettings.readText(),
        )
    }

    @Test
    fun setupDryRunDoesNotLeakPriorValueWhenFixthisEntryAlreadyExists() {
        // Changed-entry case: an existing fixthis entry is replaced with a new value.
        // DryRunDiff's renderJsonStructured emits `~ "fixthis": <after value>` — the
        // BEFORE value is omitted by design. Verifies that design guarantee end-to-end.
        val priorValue = "PRIOR-FIXTHIS-VALUE-DO-NOT-LEAK"
        val projectRoot = temporaryFolder.newFolder("project")
        val claudeSettings = projectRoot.resolve(".claude/settings.json").apply {
            parentFile.mkdirs()
            writeText("""{"mcpServers":{"fixthis":{"command":"$priorValue","args":[]}}}""")
        }
        val userHome = temporaryFolder.newFolder("home")

        val captured = captureStdout {
            withUserHome(userHome) {
                SetupCommand().parse(
                    listOf(
                        "--package", "io.github.beyondwin.fixthis.sample",
                        "--project-dir", projectRoot.absolutePath,
                        "--write",
                        "--target", "claude",
                        "--dry-run",
                    ),
                )
            }
        }

        assertFalse(
            "prior fixthis-entry value must not leak when entry is replaced. Got: $captured",
            captured.contains(priorValue),
        )
        assertTrue(
            "expected the changed fixthis entry to appear in diff. Got: $captured",
            captured.contains("fixthis"),
        )
        assertEquals(
            "dry-run must not modify the on-disk file",
            """{"mcpServers":{"fixthis":{"command":"$priorValue","args":[]}}}""",
            claudeSettings.readText(),
        )
    }

    private fun captureStdout(block: () -> Unit): String {
        val buffer = java.io.ByteArrayOutputStream()
        val previous = System.out
        System.setOut(java.io.PrintStream(buffer))
        try {
            block()
        } finally {
            System.setOut(previous)
        }
        return buffer.toString()
    }

    @Test
    fun applyWritePlanDryRunPrintsDiffOnly() {
        val marker = "EXISTING-MARKER-123"
        val tempFile = java.io.File.createTempFile("ft-cfg", ".json").apply {
            writeText("""{"mcpServers":{"other":{"command":"$marker"}}}""")
        }
        val out = java.io.ByteArrayOutputStream()
        val oldOut = System.out
        System.setOut(java.io.PrintStream(out))
        try {
            SetupCommand().applyWritePlanForTest(
                writerName = "claude",
                scope = "project-local",
                configFile = tempFile,
                content = """{"mcpServers":{"other":{"command":"$marker"},"fixthis":{"command":"y"}}}""",
                dryRun = true,
            )
        } finally {
            System.setOut(oldOut)
        }
        val captured = out.toString()
        assertFalse("existing marker should not leak", captured.contains(marker))
        assertTrue("expected added fixthis entry in diff", captured.contains("fixthis"))
    }

    @Test
    fun fullDiffFlagDisablesByteBudget() {
        // Build content that overflows the default 4 KiB JSON budget.
        // Use TOML format because JSON-parsing rejects non-JSON content
        // (and JSON budget overflow is hard to trigger from a single new mcpServer entry).
        val tempFile = java.io.File.createTempFile("ft-cfg", ".toml").apply { writeText("") }
        val largeContent = (1..600).joinToString("\n") { "[section_$it]" }
        val out = java.io.ByteArrayOutputStream()
        val oldOut = System.out
        System.setOut(java.io.PrintStream(out))
        try {
            SetupCommand().applyWritePlanForTest(
                writerName = "codex",
                scope = "global",
                configFile = tempFile,
                content = largeContent,
                dryRun = true,
                fullDiff = true,
            )
        } finally {
            System.setOut(oldOut)
        }
        val captured = out.toString()
        assertFalse("--full-diff should disable elision footer", captured.contains("elided"))
        assertTrue(
            "expected full content (>4KiB) when --full-diff is set",
            captured.toByteArray(Charsets.UTF_8).size > 4096,
        )
    }

    @Test
    fun setupWriteIsAtomicWhenSecondPlanFails() {
        val tempProject = temporaryFolder.newFolder("ft-atomic")
        val claudeFile = java.io.File(tempProject, ".claude/settings.json")
        val codexFile = java.io.File(tempProject, "fake-codex/config.toml").apply {
            parentFile.mkdirs()
            writeText("[existing]\nkey=\"value\"\n")
        }
        val original = codexFile.readText()

        // Make codex parent unwritable to force commit-phase failure on the codex plan.
        val unwritableDir = java.io.File(tempProject, "unwritable-parent").apply { mkdirs() }
        val unwritableTarget = java.io.File(unwritableDir, "child.toml").apply { writeText("[existing]\n") }
        unwritableDir.setWritable(false, false)

        try {
            SetupCommand().runWritePlansAtomicForTest(
                plans = listOf(
                    Triple("claude", "project-local", claudeFile) to """{"mcpServers":{"fixthis":{"command":"x"}}}""",
                    Triple("codex-unwritable", "global", unwritableTarget) to "[mcp_servers.fixthis]\ncommand = \"x\"\n",
                ),
            )
            org.junit.Assert.fail("expected commit-phase failure")
        } catch (_: Exception) {
            // expected
        } finally {
            unwritableDir.setWritable(true, false)
        }
        // Atomic guarantee: claude file must NOT exist (rolled back).
        assertFalse(
            "claude write should be rolled back when codex write fails",
            claudeFile.exists(),
        )
        // codex untouched
        assertEquals(
            "codex file should be untouched",
            original,
            codexFile.readText(),
        )
    }

    @Test
    fun runWritePlansAtomicRollsBackFirstCommitWhenSecondMoveFails() {
        // P1: Phase 2 commit-failure path — inject Files.move that throws on the second commit.
        // Verifies the first plan's already-committed file is restored from its rollback snapshot.
        val projectRoot = temporaryFolder.newFolder("project")
        val firstTarget = java.io.File(projectRoot, ".claude/settings.json").apply {
            parentFile.mkdirs()
            writeText("""{"existing":"v1"}""")
        }
        val secondTarget = java.io.File(projectRoot, ".codex/config.toml").apply {
            parentFile.mkdirs()
            writeText("key = \"v1\"\n")
        }
        val firstOriginal = firstTarget.readText()
        val secondOriginal = secondTarget.readText()

        var moveCount = 0
        val injectedMove: (Path, Path, Array<out CopyOption>) -> Path = { src, tgt, opts ->
            moveCount++
            if (moveCount == 2) throw IOException("simulated commit failure on plan #$moveCount")
            Files.move(src, tgt, *opts)
        }

        val error = assertThrows(CliktError::class.java) {
            SetupCommand().runWritePlansAtomicForTest(
                plans = listOf(
                    Triple("claude", "project-local", firstTarget) to """{"existing":"v2"}""",
                    Triple("codex", "global", secondTarget) to "key = \"v2\"\n",
                ),
                move = injectedMove,
            )
        }

        assertEquals(
            "first committed file must be restored to original content",
            firstOriginal,
            firstTarget.readText(),
        )
        assertEquals(
            "second target (whose move failed) must keep original content",
            secondOriginal,
            secondTarget.readText(),
        )
        assertNotNull("CliktError must preserve commit cause for --verbose", error.cause)
        assertTrue("cause should be the injected IOException", error.cause is IOException)
        assertFalse(
            "no .fixthis-staging files should remain",
            Files.list(firstTarget.parentFile.toPath()).use { paths ->
                paths.anyMatch { it.fileName.toString().endsWith(".fixthis-staging") }
            },
        )
        assertFalse(
            "no .fixthis-rollback files should remain",
            Files.list(firstTarget.parentFile.toPath()).use { paths ->
                paths.anyMatch { it.fileName.toString().endsWith(".fixthis-rollback") }
            },
        )
    }

    @Test
    fun runWritePlansAtomicStagingFailureAttachesCause() {
        // P5: staging-phase CliktError must carry cause= for --verbose diagnostic context.
        val projectRoot = temporaryFolder.newFolder("project")
        val unwritable = java.io.File(projectRoot, "unwritable").apply { mkdirs() }
        val target = java.io.File(unwritable, "cfg.json")
        unwritable.setWritable(false, false)
        try {
            val error = assertThrows(CliktError::class.java) {
                SetupCommand().runWritePlansAtomicForTest(
                    plans = listOf(Triple("test", "scope", target) to "content"),
                )
            }
            assertNotNull("staging-failure CliktError must carry cause", error.cause)
        } finally {
            unwritable.setWritable(true, false)
        }
    }

    @Test
    fun runWritePlansAtomicCommitFailureAttachesCause() {
        // P5: commit-phase CliktError must carry cause= for --verbose diagnostic context.
        val target = temporaryFolder.newFile("cfg.json").apply { writeText("original") }
        val error = assertThrows(CliktError::class.java) {
            SetupCommand().runWritePlansAtomicForTest(
                plans = listOf(Triple("test", "scope", target) to "new"),
                move = { _, _, _ -> throw IOException("simulated commit failure") },
            )
        }
        assertNotNull("commit-failure CliktError must carry cause", error.cause)
        assertEquals("target must be restored on commit failure", "original", target.readText())
    }

    @Test
    fun runWritePlansAtomicFsyncsStagingFileBeforeMoveAndParentDirectoryAfter() {
        // P2: durability — staging file must be fsync'd before move; parent dir fsync'd after.
        val target = temporaryFolder.newFile("cfg.json").apply { writeText("original") }
        val events = mutableListOf<String>()
        SetupCommand().runWritePlansAtomicForTest(
            plans = listOf(Triple("test", "scope", target) to "new"),
            forceFile = { path ->
                assertTrue(
                    "forceFile must target the staging file, got $path",
                    path.fileName.toString().endsWith(".fixthis-staging"),
                )
                events += "force-staging"
            },
            forceDirectory = { path ->
                assertEquals(
                    "forceDirectory must target the config file's parent",
                    target.parentFile.toPath(),
                    path,
                )
                events += "force-parent"
            },
        )
        assertEquals(
            "fsync ordering: staging file forced before move, parent dir forced after",
            listOf("force-staging", "force-parent"),
            events,
        )
        assertEquals("file content committed", "new", target.readText())
    }

    @Test
    fun runWritePlansAtomicRollbackCreationFailureCleansUpAndAttachesCause() {
        // P0: rollback-creation loop must be wrapped in try/catch with best-effort cleanup
        // and cause preservation. Inject a copyForRollback that throws to exercise the path.
        val target = temporaryFolder.newFile("cfg.json").apply { writeText("original") }
        val error = assertThrows(CliktError::class.java) {
            SetupCommand().runWritePlansAtomicForTest(
                plans = listOf(Triple("test", "scope", target) to "new"),
                copyForRollback = { _, _ -> throw IOException("simulated rollback-snapshot failure") },
            )
        }
        assertNotNull("rollback-snapshot CliktError must carry cause", error.cause)
        assertEquals("target untouched when rollback-creation fails", "original", target.readText())
        assertFalse(
            "no .fixthis-staging files should remain",
            Files.list(target.parentFile.toPath()).use { paths ->
                paths.anyMatch { it.fileName.toString().endsWith(".fixthis-staging") }
            },
        )
        assertFalse(
            "no .fixthis-rollback files should remain",
            Files.list(target.parentFile.toPath()).use { paths ->
                paths.anyMatch { it.fileName.toString().endsWith(".fixthis-rollback") }
            },
        )
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
                        "io.github.beyondwin.fixthis.sample",
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
