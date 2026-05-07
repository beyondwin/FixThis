package io.beyondwin.fixthis.compose.console.studio.canvas

import io.beyondwin.fixthis.compose.core.model.ScreenshotInfo
import io.beyondwin.fixthis.compose.console.studio.canvas.toolbar.annotateHintText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewScreenshotStateTest {
    @Test
    fun fullPreviewPathCandidatesPreferDeviceFullPath() {
        val screenshot = ScreenshotInfo(
            fullPath = "/device/full.png",
            desktopFullPath = "/desktop/full.png",
        )

        assertEquals(
            listOf("/device/full.png", "/desktop/full.png"),
            screenshot.fullPreviewPathCandidates(),
        )
    }

    @Test
    fun fullPreviewPathCandidatesRemoveDuplicates() {
        val screenshot = ScreenshotInfo(
            fullPath = "/same/full.png",
            desktopFullPath = "/same/full.png",
        )

        assertEquals(listOf("/same/full.png"), screenshot.fullPreviewPathCandidates())
    }

    @Test
    fun fullPreviewPathCandidatesIgnoreBlankPaths() {
        val screenshot = ScreenshotInfo(
            fullPath = "  ",
            desktopFullPath = "/desktop/full.png",
        )

        assertEquals(listOf("/desktop/full.png"), screenshot.fullPreviewPathCandidates())
    }

    @Test
    fun fullPreviewPathCandidatesIgnoreCropPaths() {
        val screenshot = ScreenshotInfo(
            cropPath = "/device/crop.png",
            desktopCropPath = "/desktop/crop.png",
        )

        assertEquals(emptyList<String>(), screenshot.fullPreviewPathCandidates())
        assertEquals(emptyList<String>(), null.fullPreviewPathCandidates())
    }

    @Test
    fun previewBitmapSampleSizeUsesPowerOfTwoDownsampling() {
        assertEquals(1, calculatePreviewBitmapSampleSize(width = 900, height = 1600, maxDimension = 1600))
        assertEquals(2, calculatePreviewBitmapSampleSize(width = 1800, height = 3200, maxDimension = 1600))
        assertEquals(4, calculatePreviewBitmapSampleSize(width = 4096, height = 8192, maxDimension = 1600))
    }

    @Test
    fun previewBitmapSampleSizeFallsBackToOneForInvalidDimensions() {
        assertEquals(1, calculatePreviewBitmapSampleSize(width = 0, height = 1600, maxDimension = 1600))
        assertEquals(1, calculatePreviewBitmapSampleSize(width = 900, height = -1, maxDimension = 1600))
        assertEquals(1, calculatePreviewBitmapSampleSize(width = 900, height = 1600, maxDimension = 0))
    }

    @Test
    fun initialFullPreviewDecodeStateStartsIdleWithoutPathsAndLoadingWithCandidates() {
        assertEquals(FullPreviewDecodeState.NoScreenshot, initialFullPreviewDecodeState(emptyList()))
        assertEquals(
            FullPreviewDecodeState.Loading(listOf("/device/full.png", "/desktop/full.png")),
            initialFullPreviewDecodeState(listOf("/device/full.png", "/desktop/full.png")),
        )
    }

    @Test
    fun nonNullScreenshotWithOnlyBlankFullPathsStartsDecodeFailed() {
        val screenshot = ScreenshotInfo(
            fullPath = "  ",
            desktopFullPath = "",
        )

        val state = initialFullPreviewDecodeState(
            paths = screenshot.fullPreviewPathCandidates(),
            hasScreenshotMetadata = true,
        )

        assertEquals(FullPreviewDecodeState.DecodeFailed(emptyList()), state)
        assertEquals(
            CanvasInteractionMode.AnnotationUnavailable,
            previewInteractionMode(state.decodeStatus),
        )
    }

    @Test
    fun loadFullPreviewKeepsNullScreenshotAsNoScreenshotAndBlankMetadataAsDecodeFailed() = runBlocking {
        assertEquals(
            FullPreviewDecodeState.NoScreenshot,
            loadFullPreview(paths = emptyList(), hasScreenshotMetadata = false, onSuccess = {}),
        )
        assertEquals(
            FullPreviewDecodeState.DecodeFailed(emptyList()),
            loadFullPreview(paths = emptyList(), hasScreenshotMetadata = true, onSuccess = {}),
        )
    }

    @Test
    fun annotateHintMatchesWidgetSnapCapability() {
        assertEquals(
            "Click a widget — or drag to draw a region",
            annotateHintText(CanvasInteractionMode.WidgetSnapAndRegion),
        )
        assertEquals("Drag to draw a region", annotateHintText(CanvasInteractionMode.RegionOnly))
        assertEquals("Annotation unavailable", annotateHintText(CanvasInteractionMode.AnnotationUnavailable))
    }

    @Test
    fun interactionModeFollowsFullPreviewDecodeState() {
        assertEquals(
            CanvasInteractionMode.WidgetSnapAndRegion,
            previewInteractionMode(PreviewScreenshotDecodeStatus.NoScreenshot),
        )
        assertEquals(
            CanvasInteractionMode.RegionOnly,
            previewInteractionMode(PreviewScreenshotDecodeStatus.DecodedScreenshot),
        )
        assertEquals(
            CanvasInteractionMode.AnnotationUnavailable,
            previewInteractionMode(PreviewScreenshotDecodeStatus.Loading),
        )
        assertEquals(
            CanvasInteractionMode.AnnotationUnavailable,
            previewInteractionMode(PreviewScreenshotDecodeStatus.DecodeFailed),
        )
    }
}
