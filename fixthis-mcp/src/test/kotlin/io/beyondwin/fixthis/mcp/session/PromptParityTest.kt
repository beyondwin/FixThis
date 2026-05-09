package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.compose.core.format.DetailMode
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PromptParityTest {

    /**
     * Tokens whose lines are expected to be byte-identical between Kotlin and JS renderers.
     * Lines containing `box=` are excluded because Kotlin emits floats (10.0,20.0) while
     * JS emits integers (10,20) — a known LTRB-format divergence.
     */
    private val STABLE_LINE_TOKENS = listOf(
        "conf=", "instance", "note:", "targetRisk=",
        "viewport:", "activity:", "Screen ", "[!]", "Rule:",
        "screenshot:", "crop:", "Source root:",
    )

    /**
     * Returns true if the line should be compared for parity.
     * Lines containing `box=` are excluded due to LTRB float/int format divergence.
     */
    private fun isParityLine(line: String): Boolean =
        !line.contains("box=") && STABLE_LINE_TOKENS.any { line.contains(it) }

    @Test
    fun jsAndKotlinCompactPromptsMatchExpectedFixture() {
        val resourceRoot = File("src/test/resources/parity")
        val sessionFile = File(resourceRoot, "session.json")
        val runner = File(resourceRoot, "run-prompt.js")
        assumeTrue("parity fixtures present", sessionFile.exists() && runner.exists())

        val sessionText = sessionFile.readText()
        val session = fixThisJson.decodeFromString(SessionDto.serializer(), sessionText)
        val kotlinMarkdown = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)

        // Compare only byte-stable lines (skipping box= lines due to float/int divergence)
        val kotlinParityLines = kotlinMarkdown.lines().filter { isParityLine(it) }

        assumeTrue("Node must be on PATH for JS comparison", nodeOnPath())

        val process = ProcessBuilder("node", runner.absolutePath, sessionFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val jsOutput = process.inputStream.bufferedReader().readText().trimEnd('\n')
        process.waitFor()
        val jsParityLines = jsOutput.lines().filter { isParityLine(it) }

        assertEquals(
            "v1 parity: byte-stable lines must match between Kotlin and JS renderers",
            kotlinParityLines,
            jsParityLines,
        )
    }

    @Test
    fun kotlinAndJsCompactPromptsMatch_v2() {
        val resourceRoot = File("src/test/resources/parity")
        val sessionFile = File(resourceRoot, "session-v2.json")
        val runner = File(resourceRoot, "run-prompt.js")
        assumeTrue("v2 parity fixtures present", sessionFile.exists() && runner.exists())

        val sessionText = sessionFile.readText()
        val session = fixThisJson.decodeFromString(SessionDto.serializer(), sessionText)
        val kotlinMarkdown = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)

        // Compare only byte-stable lines (skipping box= lines due to float/int divergence)
        val kotlinParityLines = kotlinMarkdown.lines().filter { isParityLine(it) }

        assumeTrue("Node must be on PATH for JS comparison", nodeOnPath())

        val process = ProcessBuilder("node", runner.absolutePath, sessionFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val jsOutput = process.inputStream.bufferedReader().readText().trimEnd('\n')
        process.waitFor()
        val jsParityLines = jsOutput.lines().filter { isParityLine(it) }

        assertEquals(
            "v2 parity: byte-stable lines must match between Kotlin and JS renderers\n" +
                "Kotlin stable lines:\n${kotlinParityLines.joinToString("\n")}\n" +
                "JS stable lines:\n${jsParityLines.joinToString("\n")}",
            kotlinParityLines,
            jsParityLines,
        )
    }

    /**
     * Drives both renderers against a fixture whose candidates share a common
     * `src/main/java/...` directory prefix, asserting the JS and Kotlin source-root
     * algorithms agree byte-for-byte on the `Source root:` header and the stripped
     * candidate paths.
     *
     * Without this fixture the existing parity fixtures (bare filenames) never
     * exercise the source-root code path on the JS side.
     */
    @Test
    fun kotlinAndJsSourceRootStrippingMatch() {
        val resourceRoot = File("src/test/resources/parity")
        val sessionFile = File(resourceRoot, "session-srcroot.json")
        val runner = File(resourceRoot, "run-prompt.js")
        assumeTrue("source-root parity fixture present", sessionFile.exists() && runner.exists())

        val sessionText = sessionFile.readText()
        val session = fixThisJson.decodeFromString(SessionDto.serializer(), sessionText)
        val kotlinMarkdown = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)

        val kotlinParityLines = kotlinMarkdown.lines().filter { isParityLine(it) }

        assumeTrue("Node must be on PATH for JS comparison", nodeOnPath())

        val process = ProcessBuilder("node", runner.absolutePath, sessionFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val jsOutput = process.inputStream.bufferedReader().readText().trimEnd('\n')
        process.waitFor()
        val jsParityLines = jsOutput.lines().filter { isParityLine(it) }

        assertEquals(
            "source-root parity: byte-stable lines must match between Kotlin and JS renderers\n" +
                "Kotlin stable lines:\n${kotlinParityLines.joinToString("\n")}\n" +
                "JS stable lines:\n${jsParityLines.joinToString("\n")}",
            kotlinParityLines,
            jsParityLines,
        )

        // Belt-and-suspenders: confirm both outputs actually emit the Source root header.
        // Without this, the test would silently pass if the algorithm regressed to "never emit".
        assertEquals(
            "Kotlin output must emit a 'Source root:' header for fixtures with a common prefix",
            true,
            kotlinParityLines.any { it.contains("Source root:") },
        )
        assertEquals(
            "JS output must emit a 'Source root:' header for fixtures with a common prefix",
            true,
            jsParityLines.any { it.contains("Source root:") },
        )
    }

    private fun nodeOnPath(): Boolean = try {
        val process = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
        process.waitFor()
        process.exitValue() == 0
    } catch (_: Throwable) {
        false
    }
}
