package io.github.beyondwin.fixthis.compose.core.domain.snapshot

import io.github.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotExtensionTest {
    @Test
    fun defaultSnapshotLeavesNewLifecycleFieldsNull() {
        val snapshot =
            Snapshot(
                id = SnapshotId("snap-1"),
                capturedAtEpochMillis = 1_000L,
                displayName = "MainActivity",
            )

        assertNull(snapshot.orientation)
        assertNull(snapshot.widthPx)
        assertNull(snapshot.heightPx)
        assertNull(snapshot.densityDpi)
        assertNull(snapshot.windowMode)
        assertNull(snapshot.systemUiVisible)
        assertNull(snapshot.systemUiKind)
        assertNull(snapshot.fingerprint)
    }

    @Test
    fun snapshotRoundTripsAllNewLifecycleFields() {
        val snapshot =
            Snapshot(
                id = SnapshotId("snap-2"),
                capturedAtEpochMillis = 2_000L,
                displayName = "DetailsActivity",
                orientation = ScreenOrientation.LANDSCAPE,
                widthPx = 2400,
                heightPx = 1080,
                densityDpi = 420,
                windowMode = WindowMode.SPLIT_SCREEN,
                systemUiVisible = true,
                systemUiKind = "dialog",
                fingerprint = "abc123",
            )

        assertEquals(ScreenOrientation.LANDSCAPE, snapshot.orientation)
        assertEquals(2400, snapshot.widthPx)
        assertEquals(1080, snapshot.heightPx)
        assertEquals(420, snapshot.densityDpi)
        assertEquals(WindowMode.SPLIT_SCREEN, snapshot.windowMode)
        assertEquals(true, snapshot.systemUiVisible)
        assertEquals("dialog", snapshot.systemUiKind)
        assertEquals("abc123", snapshot.fingerprint)
    }

    @Test
    fun screenOrientationEnumExposesAllSupportedRotations() {
        val names = ScreenOrientation.values().map { it.name }.toSet()

        assertTrue(names.contains("PORTRAIT"))
        assertTrue(names.contains("LANDSCAPE"))
        assertTrue(names.contains("REVERSE_PORTRAIT"))
        assertTrue(names.contains("REVERSE_LANDSCAPE"))
        assertEquals(4, ScreenOrientation.values().size)
    }

    @Test
    fun windowModeEnumExposesAllSupportedModes() {
        val names = WindowMode.values().map { it.name }.toSet()

        assertTrue(names.contains("FULLSCREEN"))
        assertTrue(names.contains("SPLIT_SCREEN"))
        assertTrue(names.contains("FREEFORM"))
        assertTrue(names.contains("PIP"))
        assertEquals(4, WindowMode.values().size)
    }
}
