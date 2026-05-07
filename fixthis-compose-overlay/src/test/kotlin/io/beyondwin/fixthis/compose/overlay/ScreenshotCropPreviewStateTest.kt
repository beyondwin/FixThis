package io.beyondwin.fixthis.compose.overlay

import io.beyondwin.fixthis.compose.core.model.ScreenshotInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotCropPreviewStateTest {
    @Test
    fun cropPathCandidatesPreferAndroidLocalCropPath() {
        val screenshot = ScreenshotInfo(
            cropPath = "/device/crop.png",
            desktopCropPath = "/desktop/crop.png",
        )

        assertEquals(
            listOf("/device/crop.png", "/desktop/crop.png"),
            screenshot.cropPreviewPathCandidates(),
        )
    }

    @Test
    fun cropPathCandidatesUseDesktopCropPathWhenOnlyPathAvailable() {
        val screenshot = ScreenshotInfo(desktopCropPath = "/desktop/crop.png")

        assertEquals(listOf("/desktop/crop.png"), screenshot.cropPreviewPathCandidates())
    }

    @Test
    fun cropPathCandidatesUseDeviceCropPathWhenOnlyPathAvailable() {
        val screenshot = ScreenshotInfo(cropPath = "/device/crop.png")

        assertEquals(listOf("/device/crop.png"), screenshot.cropPreviewPathCandidates())
    }

    @Test
    fun cropPathCandidatesRemoveDuplicates() {
        val screenshot = ScreenshotInfo(
            cropPath = "/same/crop.png",
            desktopCropPath = "/same/crop.png",
        )

        assertEquals(listOf("/same/crop.png"), screenshot.cropPreviewPathCandidates())
    }

    @Test
    fun cropPathCandidatesAreEmptyWhenNoCropExists() {
        assertEquals(emptyList<String>(), ScreenshotInfo(fullPath = "/device/full.png").cropPreviewPathCandidates())
        assertEquals(emptyList<String>(), null.cropPreviewPathCandidates())
    }

    @Test
    fun bitmapSampleSizeUsesPowerOfTwoDownsampling() {
        assertEquals(1, calculateBitmapSampleSize(width = 400, height = 300, maxDimension = 720))
        assertEquals(2, calculateBitmapSampleSize(width = 1440, height = 900, maxDimension = 720))
        assertEquals(4, calculateBitmapSampleSize(width = 4096, height = 2048, maxDimension = 720))
    }
}
