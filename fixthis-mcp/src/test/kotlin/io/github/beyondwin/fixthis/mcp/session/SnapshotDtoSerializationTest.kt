package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.toDomainSnapshot
import io.github.beyondwin.fixthis.mcp.session.dto.toSnapshotDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies that [SnapshotDto] supports the SIF-1 extension fields added in Bridge
 * protocol 1.3 while remaining backwards-compatible with 1.2 payloads.
 *
 * The eight new fields (orientation, widthPx, heightPx, densityDpi, windowMode,
 * systemUiVisible, systemUiKind, fingerprint) are all nullable with default null
 * so legacy payloads written before the bump deserialize without error.
 */
class SnapshotDtoSerializationTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `legacy 1_2 payload deserializes with null new fields`() {
        val legacyPayload = """
            {
              "screenId": "screen-1",
              "capturedAtEpochMillis": 1234567890,
              "displayName": "MainActivity"
            }
        """.trimIndent()

        val dto = json.decodeFromString(SnapshotDto.serializer(), legacyPayload)

        assertEquals("screen-1", dto.screenId)
        assertEquals(1234567890L, dto.capturedAtEpochMillis)
        assertEquals("MainActivity", dto.displayName)
        assertNull(dto.orientation)
        assertNull(dto.widthPx)
        assertNull(dto.heightPx)
        assertNull(dto.densityDpi)
        assertNull(dto.windowMode)
        assertNull(dto.systemUiVisible)
        assertNull(dto.systemUiKind)
        assertNull(dto.fingerprint)
    }

    @Test
    fun `1_3 payload round-trips all new fields`() {
        val original = SnapshotDto(
            screenId = "screen-2",
            capturedAtEpochMillis = 2222L,
            displayName = "DetailActivity",
            orientation = "PORTRAIT",
            widthPx = 1080,
            heightPx = 2400,
            densityDpi = 420,
            windowMode = "FULLSCREEN",
            systemUiVisible = true,
            systemUiKind = "STATUS_BAR",
            fingerprint = "abc123",
        )

        val encoded = json.encodeToString(SnapshotDto.serializer(), original)
        val decoded = json.decodeFromString(SnapshotDto.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals("PORTRAIT", decoded.orientation)
        assertEquals(1080, decoded.widthPx)
        assertEquals(2400, decoded.heightPx)
        assertEquals(420, decoded.densityDpi)
        assertEquals("FULLSCREEN", decoded.windowMode)
        assertEquals(true, decoded.systemUiVisible)
        assertEquals("STATUS_BAR", decoded.systemUiKind)
        assertEquals("abc123", decoded.fingerprint)
    }

    @Test
    fun `domain mapper round-trips snapshot integrity fields`() {
        val original = SnapshotDto(
            screenId = "screen-3",
            capturedAtEpochMillis = 3333L,
            activityName = "io.github.beyondwin.fixthis.DetailActivity",
            displayName = "DetailActivity",
            orientation = "REVERSE_LANDSCAPE",
            widthPx = 2400,
            heightPx = 1080,
            densityDpi = 440,
            windowMode = "SPLIT_SCREEN",
            systemUiVisible = false,
            systemUiKind = "GESTURAL_NAV",
            fingerprint = "fingerprint-123",
        )

        val roundTripped = original.toDomainSnapshot().toSnapshotDto()

        assertEquals("REVERSE_LANDSCAPE", roundTripped.orientation)
        assertEquals(2400, roundTripped.widthPx)
        assertEquals(1080, roundTripped.heightPx)
        assertEquals(440, roundTripped.densityDpi)
        assertEquals("SPLIT_SCREEN", roundTripped.windowMode)
        assertEquals(false, roundTripped.systemUiVisible)
        assertEquals("GESTURAL_NAV", roundTripped.systemUiKind)
        assertEquals("fingerprint-123", roundTripped.fingerprint)
    }
}
