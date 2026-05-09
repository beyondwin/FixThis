package io.beyondwin.fixthis.compose.sidekick.bridge

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeStatusAvailabilityTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `legacy constructor populates new fields with null`() {
        val status = BridgeStatus(
            activity = "MainActivity",
            rootsCount = 1,
            sidekickVersion = "1",
            bridgeProtocolVersion = "1",
            sourceIndexAvailable = true,
        )
        assertNull(status.screenInteractive)
        assertNull(status.keyguardLocked)
        assertNull(status.appForeground)
        assertNull(status.pictureInPicture)
    }

    @Test
    fun `serializing populated status emits availability fields`() {
        val status = BridgeStatus(
            activity = "MainActivity",
            rootsCount = 2,
            sidekickVersion = "1",
            bridgeProtocolVersion = "1",
            sourceIndexAvailable = true,
            capabilities = BridgeCapabilities(),
            screenInteractive = true,
            keyguardLocked = false,
            appForeground = true,
            pictureInPicture = false,
        )
        val text = json.encodeToString(BridgeStatus.serializer(), status)
        assertTrue(text.contains("\"screenInteractive\":true"))
        assertTrue(text.contains("\"keyguardLocked\":false"))
        assertTrue(text.contains("\"appForeground\":true"))
        assertTrue(text.contains("\"pictureInPicture\":false"))
    }

    @Test
    fun `deserializing legacy payload yields null availability fields`() {
        val legacy = """
            {
              "activity": "MainActivity",
              "rootsCount": 1,
              "sidekickVersion": "1",
              "bridgeProtocolVersion": "1",
              "sourceIndexAvailable": true
            }
        """.trimIndent()
        val status = json.decodeFromString(BridgeStatus.serializer(), legacy)
        assertEquals("MainActivity", status.activity)
        assertNull(status.screenInteractive)
        assertNull(status.keyguardLocked)
        assertNull(status.appForeground)
        assertNull(status.pictureInPicture)
    }
}
