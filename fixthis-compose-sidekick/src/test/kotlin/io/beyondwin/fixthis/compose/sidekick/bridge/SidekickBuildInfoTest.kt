package io.beyondwin.fixthis.compose.sidekick.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class SidekickBuildInfoTest {
    @Test
    fun `provider returns parsed resource values`() {
        val provider = StaticSidekickBuildInfoProvider(
            buildEpochMs = "123456789",
            gitSha = "abc123",
        )

        assertEquals(
            SidekickBuildInfo(buildEpochMs = 123456789L, gitSha = "abc123"),
            provider.current(),
        )
    }

    @Test
    fun `provider falls back for malformed resource values`() {
        val provider = StaticSidekickBuildInfoProvider(
            buildEpochMs = "not-a-long",
            gitSha = "",
        )

        assertEquals(
            SidekickBuildInfo(buildEpochMs = 0L, gitSha = "unknown"),
            provider.current(),
        )
    }

    private class StaticSidekickBuildInfoProvider(
        private val buildEpochMs: String,
        private val gitSha: String,
    ) : SidekickBuildInfoProvider {
        override fun current(): SidekickBuildInfo = parseSidekickBuildInfo(
            buildEpochMs = buildEpochMs,
            gitSha = gitSha,
        )
    }
}
