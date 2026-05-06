package io.beyondwin.fixthis.mcp.session

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScreenshotArtifactPromoterTest {
    @Test
    fun promotesPreviewScreenshotsIntoSessionArtifactDirectory() {
        val root = createTempDirectory(prefix = "fixthis-promoter-").toFile()
        val previewDir = File(root, ".fixthis/preview-cache/session-1/preview-1").apply { mkdirs() }
        val full = File(previewDir, "source-full.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val crop = File(previewDir, "source-crop.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val promoter = ScreenshotArtifactPromoter()
        val screen = SnapshotDto(
            screenId = "screen-1",
            capturedAtEpochMillis = 10L,
            displayName = "Draft",
            screenshot = SnapshotScreenshotDto(
                desktopFullPath = full.absolutePath,
                desktopCropPath = crop.absolutePath,
            ),
        )

        val promoted = promoter.promote(projectRoot = root.absolutePath, sessionId = "session-1", screen = screen)

        val screenshot = promoted.screenshot!!
        assertTrue(screenshot.desktopFullPath!!.contains(".fixthis/feedback-sessions/session-1/artifacts/screens/screen-1"))
        assertTrue(screenshot.desktopCropPath!!.contains(".fixthis/feedback-sessions/session-1/artifacts/screens/screen-1"))
        assertEquals(byteArrayOf(1, 2, 3).toList(), File(screenshot.desktopFullPath!!).readBytes().toList())
        assertEquals(byteArrayOf(4, 5, 6).toList(), File(screenshot.desktopCropPath!!).readBytes().toList())
    }

    @Test
    fun returnsOriginalScreenWhenScreenshotIsNull() {
        val root = createTempDirectory(prefix = "fixthis-promoter-").toFile()
        val promoter = ScreenshotArtifactPromoter()
        val screen = SnapshotDto(
            screenId = "screen-1",
            capturedAtEpochMillis = 10L,
            displayName = "Draft",
            screenshot = null,
        )

        val promoted = promoter.promote(projectRoot = root.absolutePath, sessionId = "session-1", screen = screen)

        assertSame(screen, promoted)
    }

    @Test
    fun preservesBlankAndNullScreenshotPaths() {
        val root = createTempDirectory(prefix = "fixthis-promoter-").toFile()
        val previewDir = File(root, ".fixthis/preview-cache/session-1/preview-1").apply { mkdirs() }
        val crop = File(previewDir, "source-crop.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val promoter = ScreenshotArtifactPromoter()
        val screen = SnapshotDto(
            screenId = "screen-1",
            capturedAtEpochMillis = 10L,
            displayName = "Draft",
            screenshot = SnapshotScreenshotDto(
                desktopFullPath = " ",
                desktopCropPath = crop.absolutePath,
                fullPath = null,
                cropPath = "device-crop.png",
            ),
        )

        val promoted = promoter.promote(projectRoot = root.absolutePath, sessionId = "session-1", screen = screen)

        val screenshot = promoted.screenshot!!
        assertEquals(" ", screenshot.desktopFullPath)
        assertTrue(screenshot.desktopCropPath!!.contains(".fixthis/feedback-sessions/session-1/artifacts/screens/screen-1"))
        assertEquals(null, screenshot.fullPath)
        assertEquals("device-crop.png", screenshot.cropPath)
    }

    @Test
    fun missingNonblankScreenshotPathThrowsClearException() {
        val root = createTempDirectory(prefix = "fixthis-promoter-").toFile()
        val missingPath = File(root, ".fixthis/preview-cache/session-1/preview-1/missing.png").absolutePath
        val promoter = ScreenshotArtifactPromoter()
        val screen = SnapshotDto(
            screenId = "screen-1",
            capturedAtEpochMillis = 10L,
            displayName = "Draft",
            screenshot = SnapshotScreenshotDto(desktopFullPath = missingPath),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            promoter.promote(projectRoot = root.absolutePath, sessionId = "session-1", screen = screen)
        }

        assertEquals("Preview screenshot artifact is missing: $missingPath", error.message)
    }
}
