package io.beyondwin.fixthis.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConsoleBundleStalenessConsistencyTest {
    private val bundle: String =
        requireNotNull(this::class.java.classLoader.getResourceAsStream("console/app.js")) {
            "console/app.js resource missing on test classpath"
        }.bufferedReader().use { it.readText() }

    @Test
    fun bundledConsoleEpochMatchesServerBuildInfo() {
        val match = Regex("""const\s+ConsoleBuildEpochMs\s*=\s*(\d+)\s*;""").find(bundle)
        assertNotNull(match, "ConsoleBuildEpochMs declaration must exist in app.js")
        val bundleEpoch = match.groupValues[1].toLong()
        assertEquals(
            BuildInfo.BUILD_EPOCH_MS,
            bundleEpoch,
            "console bundle epoch must equal BuildInfo.BUILD_EPOCH_MS within the same JAR " +
                "(otherwise the staleness banner fires on a freshly built JAR)",
        )
    }

    @Test
    fun bundledConsoleGitShaMatchesServerBuildInfo() {
        val match = Regex("""const\s+ConsoleBuildGitSha\s*=\s*'([^']*)'\s*;""").find(bundle)
        assertNotNull(match, "ConsoleBuildGitSha declaration must exist in app.js")
        val bundleSha = match.groupValues[1]
        assertEquals(
            BuildInfo.GIT_SHA,
            bundleSha,
            "console bundle git sha must equal BuildInfo.GIT_SHA within the same JAR",
        )
    }
}
