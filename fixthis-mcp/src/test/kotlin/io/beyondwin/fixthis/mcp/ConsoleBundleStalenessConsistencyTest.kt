package io.beyondwin.fixthis.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConsoleBundleStalenessConsistencyTest {
    private val metaJson: String =
        requireNotNull(this::class.java.classLoader.getResourceAsStream("console/console-build-meta.json")) {
            "console/console-build-meta.json resource missing on test classpath"
        }.bufferedReader().use { it.readText() }

    @Test
    fun sidecarHasBuildEpochMsField() {
        val obj = Json.parseToJsonElement(metaJson).jsonObject
        val buildEpochMs = obj["buildEpochMs"]?.jsonPrimitive?.long
        assertNotNull(buildEpochMs, "console-build-meta.json must have buildEpochMs field")
        // The committed sidecar carries reproducible zeros; FeedbackConsoleAssets.kt substitutes
        // real runtime values (System.currentTimeMillis() / git rev-parse HEAD) when serving.
        assertTrue(buildEpochMs >= 0, "buildEpochMs must be non-negative")
    }

    @Test
    fun sidecarHasGitShaField() {
        val obj = Json.parseToJsonElement(metaJson).jsonObject
        val gitSha = obj["gitSha"]?.jsonPrimitive?.content
        assertNotNull(gitSha, "console-build-meta.json must have gitSha field")
        assertTrue(gitSha.isNotEmpty(), "gitSha must not be empty")
    }
}
