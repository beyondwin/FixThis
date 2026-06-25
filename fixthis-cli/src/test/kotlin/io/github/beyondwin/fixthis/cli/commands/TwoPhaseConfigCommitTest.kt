package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path

class TwoPhaseConfigCommitTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun plan(writerName: String, scope: String, configFile: File, content: String) = SetupWritePlan(writerName = writerName, scope = scope, configFile = configFile, content = content)

    @Test
    fun stageThenCommitWritesPlanContent() {
        val target = temporaryFolder.newFile("cfg.json").apply { writeText("original") }
        val emitted = mutableListOf<String>()

        TwoPhaseConfigCommit(emit = { emitted += it })
            .commit(listOf(plan("test", "scope", target, "new")))

        assertEquals("plan content must be committed", "new", target.readText())
        assertTrue(
            "commit must report the written target",
            emitted.any { it.contains("Wrote test MCP config (scope): ${target.absolutePath}") },
        )
        assertFalse(
            "no .fixthis-staging files should remain after commit",
            Files.list(target.parentFile.toPath()).use { paths ->
                paths.anyMatch { it.fileName.toString().endsWith(".fixthis-staging") }
            },
        )
        assertFalse(
            "no .fixthis-rollback files should remain after commit",
            Files.list(target.parentFile.toPath()).use { paths ->
                paths.anyMatch { it.fileName.toString().endsWith(".fixthis-rollback") }
            },
        )
    }

    @Test
    fun commitReturnsCommittedPlansInsteadOfWritingGlobalState() {
        val target = temporaryFolder.newFile("cfg.json").apply { writeText("original") }

        val committed = TwoPhaseConfigCommit().commit(listOf(plan("test", "scope", target, "new")))

        assertEquals(
            "commit must return the committed plans for the caller to record",
            listOf("test"),
            committed.map { it.writerName },
        )
    }

    @Test
    fun commitSkipsPlansWhoseContentIsUnchanged() {
        val target = temporaryFolder.newFile("cfg.json").apply { writeText("same") }
        val emitted = mutableListOf<String>()

        val committed = TwoPhaseConfigCommit(emit = { emitted += it })
            .commit(listOf(plan("test", "scope", target, "same")))

        assertTrue("unchanged config must not be reported as committed", committed.isEmpty())
        assertTrue("unchanged config must not emit a write message", emitted.isEmpty())
        assertEquals("same", target.readText())
    }

    @Test
    fun commitFallsBackToCopyDeleteWhenAtomicMoveUnsupported() {
        val target = temporaryFolder.newFile("cfg.json").apply { writeText("original") }
        var sawAtomicAttempt = false
        val injectedMove: (Path, Path, Array<out CopyOption>) -> Path = { src, tgt, opts ->
            if (opts.any { it == java.nio.file.StandardCopyOption.ATOMIC_MOVE }) {
                sawAtomicAttempt = true
                throw AtomicMoveNotSupportedException(src.toString(), tgt.toString(), "simulated")
            }
            Files.move(src, tgt, *opts)
        }

        TwoPhaseConfigCommit(move = injectedMove)
            .commit(listOf(plan("test", "scope", target, "new")))

        assertTrue("atomic move must have been attempted first", sawAtomicAttempt)
        assertEquals("copy+delete fallback must still commit content", "new", target.readText())
    }

    @Test
    fun commitFailureRestoresAlreadyCommittedTargetFromRollback() {
        val projectRoot = temporaryFolder.newFolder("project")
        val firstTarget = File(projectRoot, ".claude/settings.json").apply {
            parentFile.mkdirs()
            writeText("""{"existing":"v1"}""")
        }
        val secondTarget = File(projectRoot, ".codex/config.toml").apply {
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
            TwoPhaseConfigCommit(move = injectedMove).commit(
                listOf(
                    plan("claude", "project-local", firstTarget, """{"existing":"v2"}"""),
                    plan("codex", "global", secondTarget, "key = \"v2\"\n"),
                ),
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
        assertNotNull("CliktError must preserve commit cause", error.cause)
        assertTrue("cause should be the injected IOException", error.cause is IOException)
        assertTrue(
            "error message must report rolled-back applied targets",
            error.message!!.contains("Atomic commit failed") && error.message!!.contains("(rolled back)"),
        )
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
    fun stagingFailureCleansUpAndAttachesCause() {
        val projectRoot = temporaryFolder.newFolder("project")
        val unwritable = File(projectRoot, "unwritable").apply { mkdirs() }
        val target = File(unwritable, "cfg.json")
        unwritable.setWritable(false, false)
        try {
            val error = assertThrows(CliktError::class.java) {
                TwoPhaseConfigCommit().commit(listOf(plan("test", "scope", target, "content")))
            }
            assertNotNull("staging-failure CliktError must carry cause", error.cause)
            assertTrue(
                "staging-failure message wording must be preserved",
                error.message!!.contains("Could not stage MCP config writes"),
            )
        } finally {
            unwritable.setWritable(true, false)
        }
    }

    @Test
    fun rollbackCreationFailureCleansUpAndAttachesCause() {
        val target = temporaryFolder.newFile("cfg.json").apply { writeText("original") }
        val error = assertThrows(CliktError::class.java) {
            TwoPhaseConfigCommit(
                copyForRollback = { _, _ -> throw IOException("simulated rollback-snapshot failure") },
            ).commit(listOf(plan("test", "scope", target, "new")))
        }
        assertNotNull("rollback-snapshot CliktError must carry cause", error.cause)
        assertTrue(
            "rollback-snapshot message wording must be preserved",
            error.message!!.contains("Could not prepare two-phase MCP config commit"),
        )
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
    fun fsyncsStagingFileBeforeMoveAndParentDirectoryAfter() {
        val target = temporaryFolder.newFile("cfg.json").apply { writeText("original") }
        val events = mutableListOf<String>()
        TwoPhaseConfigCommit(
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
        ).commit(listOf(plan("test", "scope", target, "new")))

        assertEquals(
            "fsync ordering: staging file forced before move, parent dir forced after",
            listOf("force-staging", "force-parent"),
            events,
        )
        assertEquals("file content committed", "new", target.readText())
    }
}
