package io.github.beyondwin.fixthis.mcp.fixture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeTrustFixtureRunnerTest {
    @Test
    fun parsesInputOutputAndStrictArgs() {
        val options = RuntimeTrustFixtureRunnerOptions.parse(
            arrayOf("--input", "/tmp/input.json", "--output", "/tmp/output.json", "--strict"),
        )

        assertEquals("/tmp/input.json", options.inputPath)
        assertEquals("/tmp/output.json", options.outputPath)
        assertEquals(true, options.strict)
    }

    @Test
    fun rejectsMissingOutputArg() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeTrustFixtureRunnerOptions.parse(arrayOf("--input", "/tmp/input.json"))
        }
    }
}
