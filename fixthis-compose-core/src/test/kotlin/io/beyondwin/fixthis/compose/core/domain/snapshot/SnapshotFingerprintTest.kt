package io.beyondwin.fixthis.compose.core.domain.snapshot

import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotFingerprintTest {
    @Test
    fun sameInputsProduceSameFingerprint() {
        val a =
            makeSnapshot(
                activity = "MainActivity",
                orientation = ScreenOrientation.PORTRAIT,
                w = 1080,
                h = 1920,
            )
        val b =
            makeSnapshot(
                activity = "MainActivity",
                orientation = ScreenOrientation.PORTRAIT,
                w = 1080,
                h = 1920,
            )

        assertEquals(SnapshotFingerprint.compute(a), SnapshotFingerprint.compute(b))
    }

    @Test
    fun rotationSwapChangesFingerprint() {
        val portrait =
            makeSnapshot(
                activity = "MainActivity",
                orientation = ScreenOrientation.PORTRAIT,
                w = 1080,
                h = 1920,
            )
        val landscape =
            makeSnapshot(
                activity = "MainActivity",
                orientation = ScreenOrientation.LANDSCAPE,
                w = 1920,
                h = 1080,
            )

        assertNotEquals(
            SnapshotFingerprint.compute(portrait),
            SnapshotFingerprint.compute(landscape),
        )
    }

    @Test
    fun thousandDistinctInputsProduceThousandDistinctFingerprints() {
        val fingerprints = mutableSetOf<String?>()
        for (i in 0 until 1000) {
            val snapshot =
                makeSnapshot(
                    activity = "Activity$i",
                    orientation = ScreenOrientation.PORTRAIT,
                    w = 1080 + i,
                    h = 1920,
                )
            fingerprints.add(SnapshotFingerprint.compute(snapshot))
        }
        assertEquals(1000, fingerprints.size)
    }

    @Test
    fun nullOrEmptySnapshotReturnsNull() {
        val empty =
            Snapshot(
                id = SnapshotId("snap-empty"),
                capturedAtEpochMillis = 0L,
                displayName = "empty",
            )

        assertNull(SnapshotFingerprint.compute(empty))
    }

    @Suppress("LongParameterList")
    private fun makeSnapshot(
        activity: String? = null,
        orientation: ScreenOrientation? = null,
        w: Int? = null,
        h: Int? = null,
        density: Int? = 420,
        windowMode: WindowMode? = WindowMode.FULLSCREEN,
        systemUiKind: String? = "default",
    ): Snapshot = Snapshot(
        id = SnapshotId("snap"),
        capturedAtEpochMillis = 0L,
        activityName = activity,
        displayName = activity ?: "snap",
        orientation = orientation,
        widthPx = w,
        heightPx = h,
        densityDpi = density,
        windowMode = windowMode,
        systemUiKind = systemUiKind,
    )
}
