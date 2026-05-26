package io.github.beyondwin.fixthis.mcp.fixture

import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
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

    @Test
    fun retriesCaptureAfterLaunchUntilBridgeIsReady() {
        val bridge = FakeFixThisBridge(
            captureErrorForAttempt = { attempt ->
                if (attempt < 3) RuntimeException("bridge socket not ready") else null
            },
        )
        val delays = mutableListOf<Long>()
        val runner = RuntimeTrustFixtureRunner(
            bridgeFactory = { bridge },
            captureRetryPolicy = RuntimeCaptureRetryPolicy(maxAttempts = 3, retryDelayMillis = 25),
            delay = { delayMillis -> delays += delayMillis },
        )

        val output = runner.run(
            RuntimeTrustFixtureInput(
                projectDir = "/repo",
                packageName = "io.github.beyondwin.fixthis.sample",
                cases = listOf(
                    RuntimeTrustCaseInput(
                        caseId = "email-label",
                        runtimeTarget = RuntimeTargetSelector(text = "Email address"),
                    ),
                ),
                strict = true,
            ),
        )

        assertEquals("evaluated", output.status)
        assertEquals(3, bridge.captureCount)
        assertEquals(listOf(25L, 25L), delays)
    }
}
