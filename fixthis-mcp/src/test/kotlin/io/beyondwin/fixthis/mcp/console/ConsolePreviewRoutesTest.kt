package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.mcp.fixtures.BlockingCaptureBridge
import io.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.beyondwin.fixthis.mcp.fixtures.LegacyScreenshotBridge
import io.beyondwin.fixthis.mcp.fixtures.SequencedSessionScreenshotBridge
import io.beyondwin.fixthis.mcp.fixtures.SessionScreenshotBridge
import io.beyondwin.fixthis.mcp.fixtures.addCapturedScreenForTest
import io.beyondwin.fixthis.mcp.fixtures.javascriptFunctionBody
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import io.beyondwin.fixthis.mcp.session.SnapshotScreenshotDto
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
            defaultPackageName = "io.beyondwin.fixthis.sample",
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
            assertTrue(connection.inputStream.bufferedReader().readText().contains("io.beyondwin.fixthis.sample"))
        } finally {
            releasePreview.countDown()
            previewThread.join(2_000)
            server.stop()
        }
    }

    @Test
    fun consoleHtmlRefreshPreviewOnlyRendersPreviewRegion() {
        val html = FeedbackConsoleAssets.indexHtml
        val refreshPreviewBody = javascriptFunctionBody(html, "refreshPreview")

        assertTrue(html.contains("function renderPreviewRegion"))
        assertTrue(html.contains("function renderSessionRegions"))
        assertTrue(html.contains("function renderInspectorRegion"))
        assertTrue(html.contains("function renderPreviewOnly"))
        assertTrue(refreshPreviewBody.contains("renderPreviewOnly();"))
        assertFalse(refreshPreviewBody.contains("render();"))
    }

    @Test
    fun consoleHtmlDoesNotAutoCapturePreviewWithoutActiveSession() {
        val html = FeedbackConsoleAssets.indexHtml
        val shouldPollPreview = javascriptFunctionBody(html, "shouldPollPreview")
        val captureScreen = javascriptFunctionBody(html, "captureScreen")
        val selectDevice = javascriptFunctionBody(html, "selectDevice")

        assertTrue(shouldPollPreview.contains("Boolean(state.session)"))
        assertTrue(captureScreen.contains("if (!state.session) return;"))
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
            defaultPackageName = "io.beyondwin.fixthis.sample",
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
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(
            Regex(
                "\\.snapshot-stage \\{[\\s\\S]*justify-content: flex-start;" +
                    "[\\s\\S]*padding: 12px 24px 24px;",
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
    fun consoleHtmlKeepsFrozenPreviewStableAndShowsPersistedScreenHistory() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("let previewRequestGeneration = 0"))
        assertTrue(html.contains("let previewRequestInFlight = null"))
        assertTrue(html.contains("const preview = await requestLivePreview();"))
        assertTrue(html.contains("const requestGeneration = ++previewRequestGeneration"))
        assertTrue(html.contains("if (addItemsFlow || requestGeneration !== previewRequestGeneration) return;"))
        assertTrue(html.contains("screenshotUrl: previewScreenshotUrl(state.preview.previewId, state.session?.sessionId || null)"))
        assertTrue(html.contains("function latestPersistedScreen()"))
        assertTrue(html.contains("const persistedScreenIds = new Set("))
        assertTrue(html.contains(".filter(screen => persistedScreenIds.has(screen.screenId))"))
        assertTrue(html.contains("function screenImageUrl(screen)"))
        assertTrue(html.contains("function latestScreen()"))
        assertTrue(html.contains("if (addItemsFlow) return addItemsFlow.screen;"))
        assertTrue(html.contains("if (focusedSavedItemId) {"))
        assertTrue(html.contains("return state.preview?.screen || latestPersistedScreen();"))
        assertFalse(html.contains("return addItemsFlow?.screen || latestPersistedScreen() || state.preview?.screen;"))
        assertTrue(html.contains("'/api/screens/' + encodeURIComponent(screenId) + '/screenshot/full'"))
        assertTrue(html.contains("if (!addItemsFlow) {"))
        assertTrue(html.contains("const focusedItem = savedEvidenceItems().find(item => item.itemId === focusedSavedItemId);"))
        assertTrue(html.contains("const sameScreenItems = savedEvidenceItems().filter(item => item.screenId === focusedItem.screenId);"))
        assertFalse(html.contains("const visibleScreen = latestScreen();"))
        assertFalse(html.contains("if (nodeUid) return visibleUids.has(nodeUid);"))
        assertFalse(html.contains("savedEvidenceItems().filter(item => item.screenId === visibleScreen.screenId)"))
        assertTrue(
            html.contains("if (sameScreenItems.length) renderSavedEvidenceOverlay(overlay, image, sameScreenItems);"),
        )
        assertFalse(html.contains("renderSavedEvidenceOverlay(overlay, image, persistedItems);"))
        assertFalse(html.contains("if (!addItemsFlow && !state.preview && persistedItems.length)"))
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

    @Test
    fun consoleHtmlLivePreviewImageUsesPreviewIdScopedScreenshotRoute() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("function previewScreenshotUrl(previewId, sessionId = state.session?.sessionId || null)"))
        assertTrue(
            html.contains(
                "return '/api/preview/' + encodeURIComponent(previewId) + '/screenshot/full' + scopedQuery(sessionId);",
            ),
        )
        assertTrue(html.contains("const src = screenImageUrl(screen);"))
        assertFalse(html.contains("const src = addItemsFlow?.screenshotUrl || '/api/preview/screenshot/full'"))
    }

    @Test
    fun consoleHtmlRefreshPreviewReusesInFlightPreviewRequest() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("let previewRequestInFlight = null"))
        assertTrue(html.contains("let previewRequestContextGeneration = 0"))
        assertTrue(html.contains("let previewRequestInFlightContextGeneration = null"))
        assertTrue(html.contains("function requestLivePreview()"))
        assertTrue(html.contains("previewRequestInFlightContextGeneration === previewRequestContextGeneration"))
        assertTrue(html.contains("const requestContextGeneration = previewRequestContextGeneration;"))
        assertTrue(html.contains("const request = requestJson('/api/preview')"))
        assertTrue(html.contains("if (previewRequestInFlight === request) {"))
        assertTrue(html.contains("previewRequestInFlightContextGeneration = null;"))
        assertTrue(html.contains("return previewRequestInFlight;"))
        assertTrue(html.contains("const preview = await requestLivePreview();"))
        assertFalse(html.contains("const preview = await requestJson('/api/preview');"))
    }

    @Test
    fun consoleHtmlAddFlowStopsPollingAndFreezesPreviewIdScopedScreenshot() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("async function startAddItemsFlow()"))
        assertTrue(html.contains("let addItemsFlowStarting = false;"))
        assertTrue(html.contains("if (addItemsFlowStarting) return;"))
        assertTrue(html.contains("addItemsFlowStarting = true;"))
        assertTrue(html.contains("addItemsFlowStarting = false;"))
        assertTrue(html.contains("stopLivePreviewPolling();"))
        assertTrue(html.contains("try {"))
        assertTrue(html.contains("const addFlowContextGeneration = previewRequestContextGeneration;"))
        assertTrue(html.contains("previewRequestGeneration++;"))
        assertTrue(html.contains("let preview = state.preview;"))
        assertTrue(html.contains("if (previewRequestInFlight || !preview) {"))
        assertTrue(html.contains("preview = await requestLivePreview();"))
        assertTrue(html.contains("if (addFlowContextGeneration !== previewRequestContextGeneration) return;"))
        assertTrue(html.contains("state.preview = preview;"))
        assertTrue(html.contains("if (!state.preview) {"))
        assertTrue(html.contains("previewId: state.preview.previewId"))
        assertTrue(html.contains("screenshotUrl: previewScreenshotUrl(state.preview.previewId, state.session?.sessionId || null)"))
        assertTrue(
            Regex(
                "finally \\{\\s+addItemsFlowStarting = false;\\s+updateComposerState\\(\\);" +
                    "\\s+if \\(!addItemsFlow\\) startLivePreviewPolling\\(\\);\\s+\\}",
            ).containsMatchIn(html),
        )
        assertTrue(html.contains("if (addItemsFlowStarting) {"))
        assertTrue(html.contains("event.preventDefault();"))
        assertTrue(
            html.contains("annotateToolButton.addEventListener('click', () => enterAnnotateMode().catch(showError));"),
        )
    }

    @Test
    fun consoleHtmlClearsSavedPreviewAndDoesNotAutoFetchWhenManual() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("state.preview = null;"))
        assertTrue(html.contains("function shouldAutoFetchPreview()"))
        assertTrue(html.contains("return configuredPreviewIntervalMs() != null && shouldPollPreview();"))
        assertTrue(
            html.contains("if (!document.hidden && shouldAutoFetchPreview()) refreshPreview().catch(showError);"),
        )
        assertTrue(html.contains("if (shouldAutoFetchPreview()) return refreshPreview();"))
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
            defaultPackageName = "io.beyondwin.fixthis.sample",
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
                defaultPackageName = "io.beyondwin.fixthis.sample",
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
                defaultPackageName = "io.beyondwin.fixthis.sample",
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
                defaultPackageName = "io.beyondwin.fixthis.sample",
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
                defaultPackageName = "io.beyondwin.fixthis.sample",
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
                defaultPackageName = "io.beyondwin.fixthis.sample",
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
    fun servesLegacyScreenshotArtifactFromCurrentSession() = runBlocking {
        val projectRoot = Files.createTempDirectory("fixthis-console").toFile()
        try {
            val artifact = projectRoot.resolve(".fixthis/artifacts/screen-1/full.png")
            artifact.parentFile.mkdirs()
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            artifact.writeBytes(pngBytes)
            val service = FeedbackSessionService(
                bridge = LegacyScreenshotBridge(artifact),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
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
}
