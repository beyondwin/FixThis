package io.github.beyondwin.fixthis.mcp.fixture

import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import io.github.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import io.github.beyondwin.fixthis.mcp.McpProtocol
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
    fun recapturesWhenFirstScreenDoesNotContainRuntimeTargetYet() {
        val bridge = FakeFixThisBridge(
            snapshotMutator = { callIndex, payload ->
                if (callIndex == 1) payload.withEmptyInspectionRoots() else payload
            },
        )
        val runner = RuntimeTrustFixtureRunner(
            bridgeFactory = { bridge },
            captureRetryPolicy = RuntimeCaptureRetryPolicy(maxAttempts = 3, retryDelayMillis = 25),
            delay = {},
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
        assertEquals(2, bridge.captureCount)
        assertEquals("email-label", output.cases.single().caseId)
        assertTrue(output.cases.single().observed?.candidates.orEmpty().isNotEmpty())
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
                            visualArea = RuntimeVisualAreaSelector(left = 130f, top = 440f, right = 220f, bottom = 520f),
                        ),
                    ),
                ),
                strict = true,
            ),
        )

        assertEquals("evaluated", output.status)
        assertEquals("low", output.cases.single().observed?.confidence)
        assertTrue(output.cases.single().observed?.warnings.orEmpty().contains("VISUAL_AREA_ONLY"))
        assertTrue(output.cases.single().observed?.warnings.orEmpty().contains("POSSIBLE_VIEW_INTEROP"))
        assertTrue(output.cases.single().observed?.boundaryContext.orEmpty().isNotEmpty())
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

    private fun JsonObject.withEmptyInspectionRoots(): JsonObject = buildJsonObject {
        forEach { (key, value) ->
            if (key == "inspection") {
                put(
                    key,
                    buildJsonObject {
                        value.jsonObject.forEach { (inspectionKey, inspectionValue) ->
                            if (inspectionKey == "roots") {
                                put("roots", JsonArray(emptyList()))
                            } else {
                                put(inspectionKey, inspectionValue)
                            }
                        }
                    },
                )
            } else {
                put(key, value)
            }
        }
    }
}
