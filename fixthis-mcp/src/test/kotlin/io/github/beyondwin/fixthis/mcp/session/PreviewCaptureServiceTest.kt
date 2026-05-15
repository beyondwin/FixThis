package io.github.beyondwin.fixthis.mcp.session

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewCaptureServiceTest {
    @Test
    fun capturePreviewWritesLivePreviewCache() = runBlocking {
        val root = tempDir("fixthis-preview-capture-cache-")
        val bridge = FakeFixThisBridge()
        val store = FeedbackSessionStore(idGenerator = sequenceIds("session-1", "preview-1", "screen-1"))
        val previewCache = PreviewSnapshotCache(3)
        val service = previewCaptureService(bridge, store, previewCache)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", root.absolutePath)

        val preview = service.capturePreview(session)

        val record = previewCache.get(session.sessionId, preview.previewId)
        assertEquals(preview, record?.snapshot)
        assertTrue(File(preview.screen.screenshot?.desktopFullPath.orEmpty()).isFile)
        assertTrue(store.getSession(session.sessionId).screens.isEmpty())
    }

    @Test
    fun previewScreenshotFileReturnsExactLivePreviewScreenshot() = runBlocking {
        val root = tempDir("fixthis-preview-capture-screenshot-")
        val bridge = FakeFixThisBridge()
        val store = FeedbackSessionStore(idGenerator = sequenceIds("session-1", "preview-1", "screen-1"))
        val previewCache = PreviewSnapshotCache(3)
        val service = previewCaptureService(bridge, store, previewCache)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", root.absolutePath)
        val preview = service.capturePreview(session)

        val screenshot = service.previewScreenshotFile(session.sessionId, preview.previewId)

        assertTrue(screenshot.isFile)
        assertEquals("screen-1-full.png", screenshot.name)
        assertTrue(
            screenshot.absolutePath.contains(".fixthis/preview-cache/${session.sessionId}/${preview.previewId}"),
        )
    }

    @Test
    fun evictedPreviewScreenshotFallsBackToRetainedDiskArtifact() = runBlocking {
        val root = tempDir("fixthis-preview-capture-evicted-")
        val bridge = FakeFixThisBridge()
        val store = FeedbackSessionStore(
            idGenerator = sequenceIds(
                "session-1",
                "preview-1",
                "screen-1",
                "preview-2",
                "screen-2",
            ),
        )
        val previewCache = PreviewSnapshotCache(1)
        val service = previewCaptureService(bridge, store, previewCache)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", root.absolutePath)
        val evictedPreview = service.capturePreview(session)
        service.capturePreview(session)

        val screenshot = service.previewScreenshotFile(session.sessionId, evictedPreview.previewId)

        assertTrue(screenshot.isFile)
        assertEquals("screen-1-full.png", screenshot.name)
    }

    @Test
    fun missingPreviewScreenshotReturnsExistingPreviewNotFoundError() = runBlocking {
        val root = tempDir("fixthis-preview-capture-missing-")
        val bridge = FakeFixThisBridge()
        val store = FeedbackSessionStore(idGenerator = sequenceIds("session-1"))
        val previewCache = PreviewSnapshotCache(1)
        val service = previewCaptureService(bridge, store, previewCache)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", root.absolutePath)

        val error = assertFailsWith<FeedbackSessionException> {
            service.previewScreenshotFile(session.sessionId, "missing-preview")
        }

        assertTrue(error.message.orEmpty().contains("PREVIEW_NOT_FOUND"))
    }

    @Test
    fun toCapturedScreenPreservesBridgeSnapshotIntegrityFieldsAndComputesFallbackFingerprint() {
        val screen = buildJsonObject {
            put("activity", "io.github.beyondwin.fixthis.MainActivity")
            put("capturedAtEpochMillis", 1_700_000_000_123L)
            put("orientation", "LANDSCAPE")
            put("widthPx", 2400)
            put("heightPx", 1080)
            put("densityDpi", 420)
            put("windowMode", "FREEFORM")
            put("systemUiVisible", true)
            put("systemUiKind", "THREE_BUTTON_NAV")
        }.toCapturedScreen(screenId = "screen-1", fallbackDisplayName = "Draft screen")

        assertEquals(1_700_000_000_123L, screen.capturedAtEpochMillis)
        assertEquals("LANDSCAPE", screen.orientation)
        assertEquals(2400, screen.widthPx)
        assertEquals(1080, screen.heightPx)
        assertEquals(420, screen.densityDpi)
        assertEquals("FREEFORM", screen.windowMode)
        assertEquals(true, screen.systemUiVisible)
        assertEquals("THREE_BUTTON_NAV", screen.systemUiKind)
        assertEquals("1eff9a3f1e5eaac5", screen.fingerprint)
    }

    @Test
    fun toCapturedScreenKeepsLegacyActivityOnlyFingerprintUnavailable() {
        val screen = buildJsonObject {
            put("activity", "io.github.beyondwin.fixthis.MainActivity")
        }.toCapturedScreen(screenId = "screen-1", fallbackDisplayName = "Draft screen")

        assertEquals("io.github.beyondwin.fixthis.MainActivity", screen.activityName)
        assertNull(screen.fingerprint)
    }

    private fun previewCaptureService(
        bridge: FakeFixThisBridge,
        store: FeedbackSessionStore,
        previewCache: PreviewSnapshotCache,
    ): PreviewCaptureService = PreviewCaptureService(
        bridge = bridge,
        store = store,
        previewCache = previewCache,
        targetEvidenceService = TargetEvidenceService(
            bridge = bridge,
            sourceIndexRegistry = SourceIndexRegistry(),
        ),
    )

    private fun tempDir(prefix: String): File = kotlin.io.path.createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }

    private fun sequenceIds(vararg values: String): () -> String {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirstOrNull() ?: error("No more ids configured") }
    }
}
