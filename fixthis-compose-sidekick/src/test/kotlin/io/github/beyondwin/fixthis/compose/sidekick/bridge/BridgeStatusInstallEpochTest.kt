package io.github.beyondwin.fixthis.compose.sidekick.bridge

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeStatusInstallEpochTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `installEpochMillis defaults to null`() {
        val status = BridgeStatus(
            activity = null,
            rootsCount = 0,
            sidekickVersion = "1.0.0",
            bridgeProtocolVersion = "1.0.0",
            sourceIndexAvailable = false,
        )
        assertNull(status.installEpochMillis)
    }

    @Test
    fun `JSON round-trip preserves installEpochMillis`() {
        val original = BridgeStatus(
            activity = "Foo",
            rootsCount = 1,
            sidekickVersion = "1.0.0",
            bridgeProtocolVersion = "1.0.0",
            sourceIndexAvailable = true,
            installEpochMillis = 1_700_000_000_000L,
        )
        val text = json.encodeToString(BridgeStatus.serializer(), original)
        val decoded = json.decodeFromString(BridgeStatus.serializer(), text)
        assertEquals(original.installEpochMillis, decoded.installEpochMillis)
    }

    @Test
    fun `legacy JSON without installEpochMillis decodes to null`() {
        val legacy = """{"rootsCount":1,"sidekickVersion":"1.0.0","bridgeProtocolVersion":"1.0.0","sourceIndexAvailable":true}"""
        val decoded = json.decodeFromString(BridgeStatus.serializer(), legacy)
        assertNull(decoded.installEpochMillis)
    }
}
