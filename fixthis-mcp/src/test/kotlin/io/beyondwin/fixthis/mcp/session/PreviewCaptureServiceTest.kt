package io.beyondwin.fixthis.mcp.session

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreviewCaptureServiceTest {
    @Test
    fun capturePreviewWritesLivePreviewCache() = runBlocking {
        val root = tempDir("fixthis-preview-capture-cache-")
        val bridge = FakeFixThisBridge()
        val store = FeedbackSessionStore(idGenerator = sequenceIds("session-1", "preview-1", "screen-1"))
        val previewCache = PreviewSnapshotCache(3)
        val service = previewCaptureService(bridge, store, previewCache)
        val session = store.openSession("io.beyondwin.fixthis.sample", root.absolutePath)

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
        val session = store.openSession("io.beyondwin.fixthis.sample", root.absolutePath)
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
        val session = store.openSession("io.beyondwin.fixthis.sample", root.absolutePath)
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
        val session = store.openSession("io.beyondwin.fixthis.sample", root.absolutePath)

        val error = assertFailsWith<FeedbackSessionException> {
            service.previewScreenshotFile(session.sessionId, "missing-preview")
        }

        assertTrue(error.message.orEmpty().contains("PREVIEW_NOT_FOUND"))
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
