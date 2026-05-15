package io.github.beyondwin.fixthis.mcp

import kotlin.test.Test
import kotlin.test.assertTrue

class BuildInfoTest {
    @Test
    fun buildInfoExposesBuildEpoch() {
        assertTrue(BuildInfo.BUILD_EPOCH_MS > 0L, "build epoch must be set")
    }

    @Test
    fun buildInfoExposesGitSha() {
        assertTrue(BuildInfo.GIT_SHA.isNotBlank(), "git sha must be set")
        assertTrue(BuildInfo.GIT_SHA.length in 4..40, "git sha length plausible")
    }
}
