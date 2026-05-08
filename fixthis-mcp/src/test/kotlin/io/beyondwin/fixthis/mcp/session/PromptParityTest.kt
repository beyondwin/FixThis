package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.compose.core.format.DetailMode
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PromptParityTest {
    @Test
    fun jsAndKotlinCompactPromptsMatchExpectedFixture() {
        val resourceRoot = File("src/test/resources/parity")
        val sessionFile = File(resourceRoot, "session.json")
        val expectedFile = File(resourceRoot, "expected-prompt.txt")
        val runner = File(resourceRoot, "run-prompt.js")
        assumeTrue("parity fixtures present", sessionFile.exists() && expectedFile.exists() && runner.exists())

        val nodeAvailable = nodeOnPath()
        val expected = expectedFile.readText().trimEnd('\n')

        val sessionText = sessionFile.readText()
        val session = fixThisJson.decodeFromString(SessionDto.serializer(), sessionText)
        val kotlinMarkdown = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)
        // Compare only the compact-token lines (trimmed to ignore indentation differences):
        val kotlinCompactLines = kotlinMarkdown.lines().filter { it.trim().startsWith("src?") }.map { it.trim() }
        val expectedCompactLines = expected.lines().filter { it.trim().startsWith("src?") }.map { it.trim() }
        assertEquals(expectedCompactLines, kotlinCompactLines)

        if (!nodeAvailable) return

        val process = ProcessBuilder("node", runner.absolutePath, sessionFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trimEnd('\n')
        process.waitFor()
        assertEquals(expected, output)
    }

    private fun nodeOnPath(): Boolean = try {
        val process = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
        process.waitFor()
        process.exitValue() == 0
    } catch (_: Throwable) {
        false
    }
}
