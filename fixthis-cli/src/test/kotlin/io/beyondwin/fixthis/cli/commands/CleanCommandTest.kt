package io.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class CleanCommandTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun dryRunListsKnownArtifactDirectoriesAndPreservesProjectJson() {
        val root = temporaryFolder.newFolder("project")
        val projectJson = root.resolve(".fixthis/project.json").also {
            it.parentFile.mkdirs()
            it.writeText("""{"packageName":"io.beyondwin.fixthis.sample"}""")
        }
        knownArtifactDirectories(root).forEach { it.mkdirs() }

        val result = runFixThis("clean", "--project-dir", root.absolutePath, "--dry-run")

        assertEquals(result.stderr, 0, result.exitCode)
        assertEquals("", result.stderr)
        assertTrue(result.stdout, result.stdout.contains(".fixthis/feedback-sessions: would delete"))
        assertTrue(result.stdout, result.stdout.contains(".fixthis/preview-cache: would delete"))
        assertTrue(result.stdout, result.stdout.contains(".fixthis/artifacts: would delete"))
        assertTrue(result.stdout, result.stdout.contains(".fixthis/smoke-reports: would delete"))
        knownArtifactDirectories(root).forEach { directory ->
            assertTrue("${directory.absolutePath} should remain after dry-run", directory.isDirectory)
        }
        assertTrue(projectJson.isFile)
    }

    @Test
    fun cleanDeletesOnlyKnownArtifactDirectories() {
        val root = temporaryFolder.newFolder("project")
        val projectJson = root.resolve(".fixthis/project.json").also {
            it.parentFile.mkdirs()
            it.writeText("""{"packageName":"io.beyondwin.fixthis.sample"}""")
        }
        val unknown = root.resolve(".fixthis/unknown-cache").also {
            it.mkdirs()
            it.resolve("keep.txt").writeText("keep")
        }
        knownArtifactDirectories(root).forEach { directory ->
            directory.mkdirs()
            directory.resolve("artifact.txt").writeText("delete")
        }

        val result = runFixThis("clean", "--project-dir", root.absolutePath)

        assertEquals(result.stderr, 0, result.exitCode)
        assertEquals("", result.stderr)
        knownArtifactDirectories(root).forEach { directory ->
            assertFalse("${directory.absolutePath} should be deleted", directory.exists())
        }
        assertTrue(projectJson.isFile)
        assertTrue(unknown.resolve("keep.txt").isFile)
    }

    @Test
    fun olderThanDaysSkipsFreshDirectories() {
        val root = temporaryFolder.newFolder("project")
        val oldDirectory = root.resolve(".fixthis/feedback-sessions").also { it.mkdirs() }
        val freshDirectory = root.resolve(".fixthis/preview-cache").also { it.mkdirs() }
        root.resolve(".fixthis/artifacts").mkdirs()
        root.resolve(".fixthis/smoke-reports").mkdirs()
        oldDirectory.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2))
        freshDirectory.setLastModified(System.currentTimeMillis())

        val result = runFixThis("clean", "--project-dir", root.absolutePath, "--dry-run", "--older-than-days", "1")

        assertEquals(result.stderr, 0, result.exitCode)
        assertTrue(result.stdout, result.stdout.contains(".fixthis/feedback-sessions: would delete"))
        assertTrue(result.stdout, result.stdout.contains(".fixthis/preview-cache: skipped"))
    }

    @Test
    fun olderThanDaysRequiresNonNegativeValue() {
        val root = temporaryFolder.newFolder("project")

        val result = runFixThis("clean", "--project-dir", root.absolutePath, "--older-than-days", "-1")

        assertEquals(1, result.exitCode)
        assertTrue(result.stderr, result.stderr.contains("--older-than-days must be non-negative"))
    }

    @Test
    fun cleanSkipsSymlinkedArtifactDirectoryWithoutDeletingTarget() {
        val root = temporaryFolder.newFolder("project")
        val externalDirectory = temporaryFolder.newFolder("external-artifacts-target")
        val externalFile = externalDirectory.resolve("keep.txt").also { it.writeText("keep") }
        val symlink = root.resolve(".fixthis/artifacts").also { it.parentFile.mkdirs() }
        createSymbolicLinkOrSkip(symlink, externalDirectory)

        val result = runFixThis("clean", "--project-dir", root.absolutePath)

        assertEquals(result.stderr, 0, result.exitCode)
        assertEquals("", result.stderr)
        assertTrue(result.stdout, result.stdout.contains(".fixthis/artifacts: skipped"))
        assertTrue("external file must not be deleted through artifact symlink", externalFile.isFile)
        assertTrue("artifact symlink should remain untouched", Files.isSymbolicLink(symlink.toPath()))
    }

    private fun knownArtifactDirectories(root: File): List<File> = listOf(
        root.resolve(".fixthis/feedback-sessions"),
        root.resolve(".fixthis/preview-cache"),
        root.resolve(".fixthis/artifacts"),
        root.resolve(".fixthis/smoke-reports"),
    )

    private fun createSymbolicLinkOrSkip(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (exception: UnsupportedOperationException) {
            assumeNoException("symbolic links are unavailable on this platform", exception)
        } catch (exception: IOException) {
            assumeNoException("symbolic links are unavailable in this test environment", exception)
        } catch (exception: SecurityException) {
            assumeNoException("symbolic links are blocked in this test environment", exception)
        }
    }

    private fun runFixThis(vararg args: String): CommandResult {
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp",
            System.getProperty("java.class.path"),
            "io.beyondwin.fixthis.cli.MainKt",
            *args,
        )
            .redirectErrorStream(false)
            .start()

        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        return CommandResult(
            exitCode = if (process.isAlive) -1 else process.exitValue(),
            stdout = if (process.isAlive) "" else process.inputStream.bufferedReader().readText(),
            stderr = if (process.isAlive) "" else process.errorStream.bufferedReader().readText(),
        )
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
