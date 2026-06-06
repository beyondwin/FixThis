package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.fixtures.BlockingCaptureBridge
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleSourceFixtures
import io.github.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.github.beyondwin.fixthis.mcp.fixtures.LegacyScreenshotBridge
import io.github.beyondwin.fixthis.mcp.fixtures.SequencedSessionScreenshotBridge
import io.github.beyondwin.fixthis.mcp.fixtures.SessionScreenshotBridge
import io.github.beyondwin.fixthis.mcp.fixtures.addCapturedScreenForTest
import io.github.beyondwin.fixthis.mcp.fixtures.javascriptFunctionBody
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PREVIEW_SAMPLE_PACKAGE = "io.github.beyondwin.fixthis.sample"

class ConsolePreviewRoutesTest {
    @Test
    fun lightweightRequestsStillRespondWhilePreviewCaptureIsWaitingOnBridge() {
        val previewStarted = CountDownLatch(1)
        val releasePreview = CountDownLatch(1)
        val bridge = BlockingCaptureBridge(previewStarted, releasePreview)
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = PREVIEW_SAMPLE_PACKAGE,
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        service.openSession(null, newSession = true)
        val previewThread = thread(isDaemon = true, name = "blocking-preview-test-request") {
            runCatching { ConsoleHttpTestClient(server.url).get("/api/preview") }
        }
        try {
            assertTrue(previewStarted.await(2, TimeUnit.SECONDS))

            val connection = ConsoleHttpTestClient(server.url).connection("/api/session")
            connection.connectTimeout = 500
            connection.readTimeout = 500

            assertEquals(200, connection.responseCode)
            assertTrue(connection.inputStream.bufferedReader().readText().contains(PREVIEW_SAMPLE_PACKAGE))
        } finally {
            releasePreview.countDown()
            previewThread.join(2_000)
            server.stop()
        }
    }

    @Test
    fun consoleHtmlRefreshPreviewOnlyRendersPreviewRegion() {
        val html = ConsoleSourceFixtures.readAll()
        val refreshPreviewBody = javascriptFunctionBody(html, "refreshPreview")
        val applyLivePreviewBody = javascriptFunctionBody(html, "applyLivePreview")

        assertTrue(html.contains("function renderPreviewRegion"))
        assertTrue(html.contains("function renderSessionRegions"))
        assertTrue(html.contains("function renderInspectorRegion"))
        assertTrue(html.contains("function renderPreviewOnly"))
        assertTrue(refreshPreviewBody.contains("applyLivePreview(preview, {"))
        assertTrue(applyLivePreviewBody.contains("renderPreviewOnly();"))
        assertFalse(refreshPreviewBody.contains("render();"))
        assertFalse(applyLivePreviewBody.contains("render();"))
    }

    @Test
    fun consoleHtmlDoesNotAutoCapturePreviewWithoutActiveSession() {
        val html = ConsoleSourceFixtures.readAll()
        val shouldPollPreview = javascriptFunctionBody(html, "shouldPollPreview")
        val captureScreen = javascriptFunctionBody(html, "captureScreen")
        val selectDevice = javascriptFunctionBody(html, "selectDevice")

        assertTrue(shouldPollPreview.contains("Boolean(state.session)"))
        assertTrue(
            Regex(
                "if \\(!state\\.session\\) \\{[\\s\\S]*?showStatus\\([\\s\\S]*?\\);\\s+return;\\s+\\}",
            ).containsMatchIn(captureScreen),
        )
        assertTrue(
            selectDevice.contains("if (state.session && userConnectionState(state.connection.current) === 'ready')"),
        )
    }

    @Test
    fun previewApiRejectsEmptyHistoryWithoutCreatingHiddenSession() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = PREVIEW_SAMPLE_PACKAGE,
        )
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val client = ConsoleHttpTestClient(server.url)
            val preview = client.connection("/api/preview")

            assertEquals(409, preview.responseCode)
            assertTrue(preview.errorStream.bufferedReader().readText().contains("NO_ACTIVE_SESSION"))
            val sessions = fixThisJson.parseToJsonElement(client.get("/api/sessions")).jsonObject
                .getValue("sessions")
                .jsonArray
            assertEquals(0, sessions.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun consoleHtmlKeepsPreviewFramePositionStableAcrossSelectAndAnnotateModes() {
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(
            Regex(
                "\\.snapshot-stage \\{[\\s\\S]*justify-content: flex-start;" +
                    "[\\s\\S]*padding: 4px 24px 24px;",
            ).containsMatchIn(html),
        )
        assertTrue(Regex("\\.annotate-hint-slot \\{[\\s\\S]*min-height: 32px;").containsMatchIn(html))
        assertFalse(
            Regex(
                "\\.snapshot-stage\\[data-tool-mode=\"annotate\"\\] \\{" +
                    "[\\s\\S]*(justify-content|padding-top)",
            ).containsMatchIn(html),
        )
    }

    @Test
    @Suppress("LongMethod")
    fun consoleHtmlKeepsFrozenPreviewStableAndShowsPersistedScreenHistory() {
        val html = ConsoleSourceFixtures.readAll()

        // Preview FSM single source of truth — counters live inside
        // previewFsm.js (createInitialPreviewState) and are dispatched
        // via previewUseCases.request().
        assertTrue(html.contains("createInitialPreviewState"))
        assertTrue(html.contains("const preview = await previewUseCases.request();"))
        assertTrue(html.contains("function capturePreviewContext()"))
        assertTrue(html.contains("function previewContextStillCurrent(context)"))
        assertTrue(html.contains("const previewContext = capturePreviewContext();"))
        assertTrue(
            html.contains(
                "if (draftFlow() || !previewContextStillCurrent(previewContext)) return;",
            ),
        )
        assertTrue(
            html.contains("screenshotUrl: ports.preview.screenshotUrl(preview.previewId, sessionId)"),
        )
        assertTrue(html.contains("function latestPersistedScreen()"))
        assertTrue(html.contains("const persistedScreenIds = new Set("))
        assertTrue(html.contains(".filter(screen => persistedScreenIds.has(screen.screenId))"))
        assertTrue(html.contains("function screenImageUrl(screen)"))
        assertTrue(html.contains("function latestScreen()"))
        assertTrue(html.contains("if (draftFlow()) return draftFlow().screen;"))
        assertTrue(html.contains("if (focusedSavedItemId) {"))
        assertTrue(html.contains("return savedScreen || state.preview?.screen || latestPersistedScreen();"))
        assertFalse(html.contains(legacyLatestScreenFallback()))
        assertTrue(html.contains("'/api/screens/' + encodeURIComponent(screenId) + '/screenshot/full'"))
        assertTrue(html.contains("if (!draftFlow()) {"))
        assertTrue(
            html.contains(
                "const focusedItem = savedEvidenceItems().find(" +
                    "item => item.itemId === focusedSavedItemId);",
            ),
        )
        assertTrue(
            html.contains(
                "const savedScreenId = focusedItem?.screenId || toolModeState.focusedSavedScreenId;",
            ),
        )
        assertTrue(
            html.contains(
                "const sameScreenItems = savedEvidenceItems().filter(" +
                    "item => item.screenId === savedScreenId);",
            ),
        )
        assertFalse(html.contains("const visibleScreen = latestScreen();"))
        assertFalse(html.contains("if (nodeUid) return visibleUids.has(nodeUid);"))
        assertFalse(html.contains("savedEvidenceItems().filter(item => item.screenId === visibleScreen.screenId)"))
        assertTrue(
            html.contains("if (sameScreenItems.length) renderSavedEvidenceOverlay(overlay, image, sameScreenItems);"),
        )
        assertFalse(html.contains("renderSavedEvidenceOverlay(overlay, image, persistedItems);"))
        assertFalse(html.contains("if (!draftFlow() && !state.preview && persistedItems.length)"))
        assertTrue(
            Regex(
                "async function openSession\\(sessionId\\)[\\s\\S]*stopLivePreviewPolling\\(\\);" +
                    "[\\s\\S]*await refresh\\(\\);",
            ).containsMatchIn(html),
        )
        assertTrue(html.contains("function savedEvidenceItems()"))
        assertFalse(html.contains("function persistedItemsForScreen(screenId)"))
        assertFalse(html.contains("escapeHtml(formatSavedEvidenceItemLabel(item, index))"))
    }

    private fun legacyLatestScreenFallback(): String = "return draftFlow()?.screen || " +
        "latestPersistedScreen() || state.preview?.screen;"

    @Test
    fun consoleHtmlLivePreviewImageUsesPreviewIdScopedScreenshotRoute() {
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(
            html.contains(
                "function previewScreenshotUrl(" +
                    "previewId, sessionId = state.session?.sessionId || null)",
            ),
        )
        assertTrue(
            html.contains(
                "return '/api/preview/' + encodeURIComponent(previewId) + '/screenshot/full' + scopedQuery(sessionId);",
            ),
        )
        assertTrue(html.contains("const src = screenImageUrl(screen);"))
        assertFalse(html.contains("const src = draftFlow()?.screenshotUrl || '/api/preview/screenshot/full'"))
    }

    @Test
    fun consoleHtmlRefreshPreviewReusesInFlightPreviewRequest() {
        val html = ConsoleSourceFixtures.readAll()

        // The in-flight dedup + race-fence now lives in the Preview FSM
        // (previewFsm.js / previewUseCases.js). Verify the FSM dispatch
        // path is wired up rather than the legacy module-level lets.
        assertTrue(html.contains("function requestLivePreview()"))
        assertTrue(html.contains("return requestJson('/api/preview');"))
        assertTrue(html.contains("function createPreviewUseCases("))
        assertTrue(html.contains("if (inFlightPromise && current.inFlight) {"))
        assertTrue(html.contains("dispatch({ type: 'REQUEST_STARTED' })"))
        assertTrue(html.contains("type: 'REQUEST_SUCCEEDED'"))
        assertTrue(html.contains("const preview = await previewUseCases.request();"))
        assertFalse(html.contains("const preview = await requestJson('/api/preview');"))
    }

    @Test
    fun consoleHtmlAddFlowStopsPollingAndFreezesPreviewIdScopedScreenshot() {
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(html.contains("async function startDraftAnnotationFlow()"))
        // draftFlowStarting is owned by the toolMode FSM.
        assertTrue(html.contains("draftFlowStarting: false,"))
        assertTrue(html.contains("if (toolMode.getState().draftFlowStarting) return;"))
        assertTrue(html.contains("toolMode.setAddItemsFlowStarting(true);"))
        assertTrue(html.contains("toolMode.setAddItemsFlowStarting(false);"))
        assertTrue(html.contains("stopLivePreviewPolling();"))
        assertTrue(html.contains("try {"))
        // Preview FSM owns request/context counters — addFlow captures the
        // preview context before awaiting and bails if the session or
        // contextGeneration changes.
        assertTrue(
            html.contains(
                "const previewContext = capturePreviewContext();",
            ),
        )
        assertTrue(html.contains("let preview = state.preview;"))
        assertTrue(html.contains("if (previewUseCases.getState().inFlight || !preview) {"))
        assertTrue(html.contains("preview = await previewUseCases.request();"))
        assertTrue(
            Regex(
                "if \\(!previewContextStillCurrent\\(previewContext\\)\\) \\{[\\s\\S]*?" +
                    "showStatus\\([\\s\\S]*?\\);\\s+return;\\s+\\}",
            ).containsMatchIn(html),
        )
        assertTrue(html.contains("setConsolePreview(preview);"))
        assertTrue(html.contains("if (!previewContextStillCurrent(previewContext) || !state.preview) {"))
        assertTrue(html.contains("startDraftFreeze(draftWorkspace"))
        assertTrue(html.contains("capture: async () => state.preview"))
        assertTrue(html.contains("sessionId: state.session?.sessionId || null"))
        assertTrue(
            html.contains("screenshotUrl: ports.preview.screenshotUrl(preview.previewId, sessionId)"),
        )
        assertTrue(
            Regex(
                "finally \\{\\s+toolMode\\.setAddItemsFlowStarting\\(false\\);\\s+updateComposerState\\(\\);" +
                    "\\s+if \\(!draftFlow\\(\\)\\) startLivePreviewPolling\\(\\);\\s+\\}",
            ).containsMatchIn(html),
        )
        assertTrue(html.contains("if (toolMode.getState().draftFlowStarting) {"))
        assertTrue(html.contains("event.preventDefault();"))
        assertTrue(
            html.contains("annotateToolButton.addEventListener('click', () => enterAnnotateMode().catch(showError));"),
        )
    }

    @Test
    fun consoleHtmlClearsSavedPreviewAndDoesNotAutoFetchWhenManual() {
        val html = ConsoleSourceFixtures.readAll()
        assertTrue(html.contains("setConsolePreview(null);"))
        assertTrue(html.contains("function shouldAutoFetchPreview()"))
        assertTrue(html.contains("function shouldAutoFetchPreviewFallback()"))
        assertTrue(html.contains("return configuredPreviewIntervalMs() != null && shouldPollPreview();"))
        assertTrue(html.contains("return shouldAutoFetchPreview() && shouldUsePreviewFallbackPolling();"))
        assertTrue(
            html.contains("if (!document.hidden && shouldAutoFetchPreviewFallback()) refreshPreview().catch(showError);"),
        )
        assertTrue(html.contains("if (shouldAutoFetchPreviewFallback()) return refreshPreview();"))
        assertFalse(html.contains("if (!document.hidden && shouldAutoFetchPreview()) refreshPreview().catch(showError);"))
        assertFalse(html.contains("if (shouldAutoFetchPreview()) return refreshPreview();"))
        assertFalse(html.contains("if (!document.hidden && shouldPollPreview()) refreshPreview().catch(showError);"))
        assertFalse(html.contains("if (shouldPollPreview()) return refreshPreview();"))
    }

    @Test
    fun previewRouteDoesNotAppendSessionScreens() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(
                clock = { 100L },
                idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
            ),
            projectRoot = "/repo",
            defaultPackageName = PREVIEW_SAMPLE_PACKAGE,
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val before = fixThisJson
                .parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/session"))
                .jsonObject

            val preview = fixThisJson
                .parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview"))
                .jsonObject
            val after = fixThisJson.parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/session")).jsonObject

            assertTrue(preview.containsKey("screen"))
            assertTrue(before.getValue("screens").jsonArray.isEmpty())
            assertTrue(after.getValue("screens").jsonArray.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun previewScreenshotRouteServesLatestPreviewPng() = runBlocking {
        val projectRoot = Files.createTempDirectory("fixthis-console-preview").toFile()
        try {
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            val service = FeedbackSessionService(
                bridge = SessionScreenshotBridge(pngBytes),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = PREVIEW_SAMPLE_PACKAGE,
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                service.openSession(null, newSession = true)
                ConsoleHttpTestClient(server.url).get("/api/preview")

                val connection = ConsoleHttpTestClient(server.url).connection("/api/preview/screenshot/full")

                assertEquals(200, connection.responseCode)
                assertEquals("image/png", connection.contentType)
                assertTrue(connection.inputStream.use { it.readBytes() }.contentEquals(pngBytes))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun previewIdScreenshotRouteServesExactPreviewPngInsteadOfLatest() = runBlocking {
        val projectRoot = Files.createTempDirectory("fixthis-console-preview-exact").toFile()
        try {
            val firstPng = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x01)
            val secondPng = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x02)
            val service = FeedbackSessionService(
                bridge = SequencedSessionScreenshotBridge(firstPng, secondPng),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds(
                        "session-1",
                        "preview-1",
                        "preview-screen-1",
                        "preview-2",
                        "preview-screen-2",
                    ).next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = PREVIEW_SAMPLE_PACKAGE,
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                service.openSession(null, newSession = true)
                val firstPreview = fixThisJson
                    .parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview"))
                    .jsonObject
                val secondPreview = fixThisJson
                    .parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview"))
                    .jsonObject
                val firstPreviewId = firstPreview.getValue("previewId").jsonPrimitive.content
                val secondPreviewId = secondPreview.getValue("previewId").jsonPrimitive.content

                val firstConnection = ConsoleHttpTestClient(server.url)
                    .connection("/api/preview/$firstPreviewId/screenshot/full")
                val secondConnection = ConsoleHttpTestClient(server.url)
                    .connection("/api/preview/$secondPreviewId/screenshot/full")

                assertEquals(200, firstConnection.responseCode)
                assertEquals("image/png", firstConnection.contentType)
                assertTrue(firstConnection.inputStream.use { it.readBytes() }.contentEquals(firstPng))
                assertEquals(200, secondConnection.responseCode)
                assertEquals("image/png", secondConnection.contentType)
                assertTrue(secondConnection.inputStream.use { it.readBytes() }.contentEquals(secondPng))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun previewIdScreenshotRouteServesEvictedPreviewPngFromDiskArtifact() = runBlocking {
        val projectRoot = Files.createTempDirectory("fixthis-console-preview-evicted").toFile()
        try {
            val firstPng = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x01)
            val service = FeedbackSessionService(
                bridge = SequencedSessionScreenshotBridge(
                    firstPng,
                    byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x02),
                    byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x03),
                    byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x04),
                ),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds(
                        "session-1",
                        "preview-1",
                        "preview-screen-1",
                        "preview-2",
                        "preview-screen-2",
                        "preview-3",
                        "preview-screen-3",
                        "preview-4",
                        "preview-screen-4",
                    ).next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = PREVIEW_SAMPLE_PACKAGE,
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                service.openSession(null, newSession = true)
                val firstPreview = fixThisJson
                    .parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview"))
                    .jsonObject
                repeat(3) { ConsoleHttpTestClient(server.url).get("/api/preview") }
                val firstPreviewId = firstPreview.getValue("previewId").jsonPrimitive.content

                val connection = ConsoleHttpTestClient(server.url)
                    .connection("/api/preview/$firstPreviewId/screenshot/full")

                assertEquals(200, connection.responseCode)
                assertEquals("image/png", connection.contentType)
                assertTrue(connection.inputStream.use { it.readBytes() }.contentEquals(firstPng))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun previewScreenshotRouteRejectsPersistedScreenshotsOutsideFixThisRoots() {
        val projectRoot = Files.createTempDirectory("fixthis-console-preview-safe").toFile()
        val outsideArtifact = Files.createTempFile("fixthis-outside", ".png").toFile()
        try {
            outsideArtifact.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = PREVIEW_SAMPLE_PACKAGE,
            )
            val session = service.openSession(null)
            service.addCapturedScreenForTest(
                session.sessionId,
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 100L,
                    displayName = "Unsafe",
                    screenshot = SnapshotScreenshotDto(desktopFullPath = outsideArtifact.absolutePath),
                ),
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = ConsoleHttpTestClient(server.url).connection("/api/preview/screenshot/full")

                assertEquals(404, connection.responseCode)
            } finally {
                server.stop()
            }
        } finally {
            outsideArtifact.delete()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun servesSessionOwnedScreenshotArtifactFromCurrentSession() = runBlocking {
        val projectRoot = Files.createTempDirectory("fixthis-console").toFile()
        try {
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            val service = FeedbackSessionService(
                bridge = SessionScreenshotBridge(pngBytes),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = PREVIEW_SAMPLE_PACKAGE,
            )
            val session = service.openSession(null)
            service.captureScreen(session.sessionId)
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = ConsoleHttpTestClient(server.url).connection("/api/screens/screen-1/screenshot/full")

                assertEquals(200, connection.responseCode)
                assertEquals("image/png", connection.contentType)
                assertTrue(connection.inputStream.use { it.readBytes() }.contentEquals(pngBytes))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun rejectsLegacyArtifactsRootScreenshotsAsNotFound() = runBlocking {
        val projectRoot = Files.createTempDirectory("fixthis-console-legacy-rejected").toFile()
        try {
            val oldArtifact = projectRoot.resolve(".fixthis/artifacts/screen-1/full.png")
            oldArtifact.parentFile.mkdirs()
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            oldArtifact.writeBytes(pngBytes)
            val service = FeedbackSessionService(
                bridge = LegacyScreenshotBridge(oldArtifact),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = PREVIEW_SAMPLE_PACKAGE,
            )
            val session = service.openSession(null)
            service.captureScreen(session.sessionId)
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = ConsoleHttpTestClient(server.url).connection("/api/screens/screen-1/screenshot/full")

                assertEquals(404, connection.responseCode)
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }
}
