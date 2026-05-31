package io.github.beyondwin.fixthis.mcp.fixture

import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import io.github.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import io.github.beyondwin.fixthis.mcp.McpProtocol
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun visualAreaRuntimeTargetCreatesAreaFeedback() {
        val bridge = FakeFixThisBridge()
        val runner = RuntimeTrustFixtureRunner(
            bridgeFactory = { bridge },
            captureRetryPolicy = RuntimeCaptureRetryPolicy(maxAttempts = 1, retryDelayMillis = 0),
            delay = {},
        )

        val output = runner.run(
            RuntimeTrustFixtureInput(
                projectDir = "/repo",
                packageName = "io.github.beyondwin.fixthis.sample",
                cases = listOf(
                    RuntimeTrustCaseInput(
                        caseId = "visual-area",
                        runtimeTarget = RuntimeTargetSelector(
                            visualArea = RuntimeVisualAreaSelector(left = 12f, top = 24f, right = 180f, bottom = 96f),
                        ),
                    ),
                ),
                strict = true,
            ),
        )

        assertEquals("evaluated", output.status)
        assertEquals("low", output.cases.single().observed?.confidence)
        assertTrue(output.cases.single().observed?.warnings.orEmpty().contains("VISUAL_AREA_ONLY"))
    }

    @Test
    fun usesHostSourceIndexPathWhenBridgeSnapshotReportsUnavailable() {
        val sourceIndexFile = kotlin.io.path.createTempFile(prefix = "fixthis-runtime-source-index-", suffix = ".json").toFile()
        sourceIndexFile.writeText(
            McpProtocol.json.encodeToString(
                SourceIndex.serializer(),
                SourceIndex(
                    entries = listOf(
                        SourceIndexEntry(
                            file = "sample/src/main/java/io/github/fixthis/sample/screens/FormScreen.kt",
                            line = 37,
                            text = listOf("Email address"),
                            testTags = listOf("emailField"),
                            activityNames = listOf("MainActivity"),
                        ),
                    ),
                ),
            ),
        )
        val bridge = FakeFixThisBridge(sourceIndexAvailable = false, sourceIndex = null)
        val runner = RuntimeTrustFixtureRunner(
            bridgeFactory = { bridge },
            captureRetryPolicy = RuntimeCaptureRetryPolicy(maxAttempts = 1, retryDelayMillis = 0),
            delay = {},
        )

        val output = runner.run(
            RuntimeTrustFixtureInput(
                projectDir = "/repo",
                packageName = "io.github.beyondwin.fixthis.sample",
                sourceIndexPath = sourceIndexFile.absolutePath,
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
        assertTrue(output.cases.single().observed?.sourceConfidence in setOf("medium", "high"))
        assertTrue(output.cases.single().observed?.candidates.orEmpty().isNotEmpty())
    }
}
