package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.session.preview.PreviewCaptureService
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewSnapshotCache
import io.github.beyondwin.fixthis.mcp.session.source.SourceIndexRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EvidenceCoordinatorTest {

    @Test
    fun captureScreenAppendsScreenToSession() = runBlocking {
        val fixture = newFixture(ids = listOf("session-1", "screen-1"))
        val session = fixture.openSession()

        val screen = fixture.coordinator.captureScreen(session.sessionId)

        assertEquals("screen-1", screen.screenId)
        assertEquals(1, fixture.store.getSession(session.sessionId).screens.size)
    }

    @Test
    fun captureScreenUsesSessionArtifactPath() = runBlocking {
        val fixture = newFixture(ids = listOf("session-1", "screen-1"), root = tempDir("evidence-coord-"))
        val session = fixture.openSession()

        fixture.coordinator.captureScreen(session.sessionId)

        assertEquals("session-1", fixture.bridge.lastCaptureSessionId)
        assertEquals("screen-1", fixture.bridge.lastCaptureScreenId)
        assertTrue(
            fixture.bridge.lastCaptureDestination.orEmpty()
                .contains(".fixthis/feedback-sessions/session-1/artifacts/screens/screen-1"),
        )
    }

    @Test
    fun navigateBackProducesNavigationResultWithoutCapture() = runBlocking {
        val fixture = newFixture(ids = listOf("session-1"))
        val session = fixture.openSession()

        val result = fixture.coordinator.navigate(
            sessionId = session.sessionId,
            request = FeedbackNavigationRequest(action = FeedbackNavigationAction.BACK, captureAfter = false),
        )

        assertTrue(result.performed)
        assertEquals(FeedbackNavigationAction.BACK, result.action)
    }

    @Test
    fun navigatePropagatesCancellationFromFollowUpCapture() {
        runBlocking {
            val fixture = newFixture(
                ids = listOf("session-1", "screen-1"),
                bridge = FakeFixThisBridge(captureError = CancellationException("capture cancelled")),
            )
            val session = fixture.openSession()

            assertFailsWith<CancellationException> {
                fixture.coordinator.navigate(
                    sessionId = session.sessionId,
                    request = FeedbackNavigationRequest(action = FeedbackNavigationAction.BACK),
                )
            }
        }
    }

    @Test
    fun capturePreviewProducesPreviewSnapshot() = runBlocking {
        val fixture = newFixture(ids = listOf("session-1", "preview-1", "screen-1"))
        val session = fixture.openSession()

        val preview = fixture.coordinator.capturePreview(session.sessionId)

        assertTrue(preview.previewId.isNotBlank())
    }

    @Test
    fun previewScreenshotFileReturnsLivePreviewArtifact() = runBlocking {
        val root = tempDir("evidence-preview-")
        val fixture = newFixture(ids = listOf("session-1", "preview-1", "screen-1"), root = root)
        val session = fixture.openSession()
        val preview = fixture.coordinator.capturePreview(session.sessionId)

        val file = fixture.coordinator.previewScreenshotFile(session.sessionId, preview.previewId)

        assertTrue(file.isFile)
    }

    private class Fixture(
        val coordinator: EvidenceCoordinator,
        val store: FeedbackSessionStore,
        val bridge: FakeFixThisBridge,
        val projectRoot: File,
    ) {
        fun openSession(): SessionDto = store.openSession(packageName = "io.github.beyondwin.fixthis.sample", projectRoot = projectRoot.absolutePath)
    }

    private fun newFixture(
        ids: List<String>,
        bridge: FakeFixThisBridge = FakeFixThisBridge(),
        root: File = File("/repo"),
    ): Fixture {
        val queue = ArrayDeque(ids)
        val store = FeedbackSessionStore(
            clock = { 100L },
            idGenerator = { queue.removeFirstOrNull() ?: error("no more ids") },
        )
        val previewCache = PreviewSnapshotCache(maxEntries = 3)
        val sourceIndexRegistry = SourceIndexRegistry()
        val targetEvidenceService = TargetEvidenceService(
            bridge = bridge,
            sourceIndexRegistry = sourceIndexRegistry,
            projectRoot = root,
        )
        val previewCaptureService = PreviewCaptureService(
            bridge = bridge,
            store = store,
            previewCache = previewCache,
            targetEvidenceService = targetEvidenceService,
        )
        val coordinator = EvidenceCoordinator(
            bridge = bridge,
            store = store,
            previewCaptureService = previewCaptureService,
        )
        return Fixture(coordinator, store, bridge, root)
    }

    private fun tempDir(prefix: String): File = kotlin.io.path.createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }
}
