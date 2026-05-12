package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpPrincipal
import io.beyondwin.fixthis.cli.AdbDevice
import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.TreeKind
import io.beyondwin.fixthis.mcp.session.AnnotationDto
import io.beyondwin.fixthis.mcp.session.AnnotationStatusDto
import io.beyondwin.fixthis.mcp.session.AnnotationTargetDto
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackDelivery
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationAction
import io.beyondwin.fixthis.mcp.session.FeedbackSessionException
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPaths
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPersistence
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import io.beyondwin.fixthis.mcp.session.SnapshotRootDto
import io.beyondwin.fixthis.mcp.session.SnapshotScreenshotDto
import io.beyondwin.fixthis.mcp.tools.FixThisBridge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackConsoleServerTest {
    @Test
    fun routeTableDispatchesFirstMatchingRoute() {
        val calls = mutableListOf<String>()
        val table = ConsoleRouteTable(
            listOf(
                object : ConsoleRoute {
                    override fun matches(path: String): Boolean = path.startsWith("/api/preview/")
                    override fun handle(exchange: HttpExchange) {
                        calls += "broad"
                    }
                },
                object : ConsoleRoute {
                    override fun matches(path: String): Boolean = path == "/api/preview/exact/screenshot/full"
                    override fun handle(exchange: HttpExchange) {
                        calls += "specific"
                    }
                },
            ),
        )

        assertTrue(table.handle(FakeExchange("/api/preview/exact/screenshot/full")))

        assertEquals(listOf("broad"), calls)
    }

    @Test
    fun servesIndexAndSessionJson() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val index = ConsoleHttpTestClient(server.url).get()
            assertTrue(index.contains("FixThis Feedback Console"))

            val client = ConsoleHttpTestClient(server.url)
            val opened = client.connection(
                "/api/session/open",
                method = "POST",
                body = """{"newSession":true}""",
            )
            assertEquals(200, opened.responseCode)
            opened.inputStream.close()

            val session = client.get("/api/session")
            assertTrue(session.contains("io.beyondwin.fixthis.sample"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionApiDoesNotCreateSessionWhenHistoryIsEmpty() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)

            assertEquals("null", client.get("/api/session"))

            val sessions = fixThisJson.parseToJsonElement(client.get("/api/sessions")).jsonObject
                .getValue("sessions")
                .jsonArray
            assertEquals(0, sessions.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun mutatingApiRequiresConsoleToken() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url, includeConsoleToken = false).connection("/api/items/draft")
            connection.requestMethod = "DELETE"

            assertEquals(403, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun browserServedConsoleTokenAllowsMutation() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val token = consoleTokenFrom(ConsoleHttpTestClient(server.url).get())
            val connection = ConsoleHttpTestClient(server.url).connection("/api/items/draft")
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("X-FixThis-Console-Token", token)

            assertEquals(200, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemPatchUpdatesDraftAnnotation() {
        val store = FeedbackSessionStore(
            clock = FakeLongs(100L, 200L, 300L, 400L).next,
            idGenerator = FakeIds("session-1", "item-1").next,
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        store.addScreen(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Screen 1",
            ),
        )
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = "screen-1",
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                comment = "Before",
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection(
                "/api/items/item-1",
                method = "PUT",
                body = """{"comment":"After","status":"in_progress"}""",
            )

            assertEquals(200, connection.responseCode)
            val payload = fixThisJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            val item = payload.getValue("items").jsonArray.single().jsonObject
            assertEquals("After", item.getValue("comment").jsonPrimitive.content)
            assertEquals("in_progress", item.getValue("status").jsonPrimitive.content)
            assertEquals("After", service.getSession("session-1").items.single().comment)
            assertEquals(AnnotationStatusDto.IN_PROGRESS, service.getSession("session-1").items.single().status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemPatchUsesRequestedSessionWhenCurrentSessionChanged() {
        val store = FeedbackSessionStore(
            clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L, 700L).next,
            idGenerator = FakeIds("session-1", "item-1", "session-2").next,
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session1 = service.openSession(null, newSession = true)
        store.addScreen(
            session1.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Screen 1",
            ),
        )
        store.addItem(
            session1.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = "screen-1",
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                comment = "Before",
            ),
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection(
                "/api/items/item-1",
                method = "PUT",
                body = """{"sessionId":"session-1","comment":"After"}""",
            )

            assertEquals(200, connection.responseCode)
            assertEquals("After", service.getSession("session-1").items.single().comment)
        } finally {
            server.stop()
        }
    }

    @Test
    fun mutatingApiRejectsForbiddenOriginEvenWithConsoleToken() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val token = consoleTokenFrom(ConsoleHttpTestClient(server.url).get())

            assertEquals(
                403,
                rawHttpResponseCode(
                    server.url,
                    method = "DELETE",
                    path = "/api/items/draft",
                    headers = mapOf(
                        ConsoleTokenHeader to token,
                        "Origin" to "https://example.invalid",
                    ),
                ),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun getApiDoesNotRequireConsoleToken() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url, includeConsoleToken = false).connection("/api/session")

            assertEquals(200, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun configuredConsoleAssetsReceiveConsoleToken() {
        val assetsDir = Files.createTempDirectory("fixthis-console-assets-token").toFile()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        writeConsoleAssets(assetsDir, marker = "token-marker")
        val server = FeedbackConsoleServer(service = service, port = 0, consoleAssetsDir = assetsDir)
        server.start()
        try {
            val index = ConsoleHttpTestClient(server.url).get()

            assertTrue(index.contains("token-marker"))
            assertTrue(consoleTokenFrom(index).isNotBlank())
        } finally {
            server.stop()
            assetsDir.deleteRecursively()
        }
    }

    @Test
    fun consoleRequestJsonSendsTokenForMutatingRequestsAndPreservesHeaders() {
        val html = FeedbackConsoleAssets.indexHtml
        val requestJsonBody = javascriptFunctionBody(html, "requestJson")

        assertTrue(html.contains("window.FixThisConsoleConfig"))
        assertTrue(requestJsonBody.contains("X-FixThis-Console-Token"))
        assertTrue(requestJsonBody.contains("new Headers(options.headers || {})"))
        assertTrue(requestJsonBody.contains("headers.set('X-FixThis-Console-Token'"))
        assertTrue(requestJsonBody.contains("fetch(path, { ...options, headers })"))
    }

    @Test
    fun browserConsoleUsesCurrentFeedbackContractLabels() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val index = ConsoleHttpTestClient(server.url).get()

            assertButtonContainsContractLabel(index, "copyPromptButton", "Copy Prompt")
            assertButtonContainsContractLabel(index, "sendAgentButton", "Save to MCP")
            assertButtonContainsContractLabel(index, "selectToolButton", "Select")
            assertButtonContainsContractLabel(index, "annotateToolButton", "Annotate")
            assertButtonContainsContractLabel(index, "addItemButton", "Add annotation")
            assertButtonContainsContractLabel(index, "cancelAddFlowButton", "Exit Annotate")
            assertButtonContainsContractLabel(index, "clearSelectionButton", "Clear Selection")
            assertButtonContainsContractLabel(index, "clearDraftButton", "Clear Draft")
            assertButtonContainsContractLabel(index, "refreshDevicesButton", "Refresh devices")
            assertButtonContainsContractLabel(index, "disconnectDeviceButton", "Clear selection")
            assertFalse(index.contains("Save snapshot"))
            assertFalse(index.contains("Add to Pending"))
        } finally {
            server.stop()
        }
    }

    private fun assertButtonContainsContractLabel(html: String, id: String, label: String) {
        val button = Regex("""<button\b(?=[^>]*\bid="${Regex.escape(id)}")[\s\S]*?</button>""")
            .find(html)
            ?.value

        assertTrue(button != null, "Expected served HTML to include button id=\"$id\"")
        assertTrue(
            button!!.contains(label),
            "Expected button id=\"$id\" to contain contract label \"$label\"",
        )
    }

    private fun consoleTokenFrom(html: String): String = Regex("consoleToken:\\s*\"([^\"]+)\"")
        .find(html)
        ?.groupValues
        ?.get(1)
        ?: throw AssertionError("Expected served console HTML to include consoleToken config")

    private fun rawHttpResponseCode(
        baseUrl: String,
        method: String,
        path: String,
        headers: Map<String, String>,
    ): Int {
        val uri = URI.create(baseUrl)
        java.net.Socket(uri.host, uri.port).use { socket ->
            val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            writer.write("$method $path HTTP/1.1\r\n")
            writer.write("Host: ${uri.host}:${uri.port}\r\n")
            writer.write("Connection: close\r\n")
            writer.write("Content-Length: 0\r\n")
            headers.forEach { (name, value) -> writer.write("$name: $value\r\n") }
            writer.write("\r\n")
            writer.flush()
            val statusLine = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
            return statusLine.split(" ")[1].toInt()
        }
    }

    @Test
    fun servesFaviconWithoutBrowserVisible404() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/favicon.ico")

            assertEquals(204, connection.responseCode)
        } finally {
            server.stop()
        }
    }

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
    fun consoleAssetsAreLoadedFromClasspathResources() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("<style>"))
        assertTrue(html.contains("</style>"))
        assertTrue(html.contains("<script>"))
        assertTrue(html.contains("</script>"))
        assertTrue(html.contains("class=\"studio-shell\""))
        assertTrue(html.contains("function renderPreviewRegion"))
    }

    @Test
    fun generatedConsoleAppMatchesConsoleSourceModules() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }
        val sourceDir = File(root, "fixthis-mcp/src/main/console")
        val modules = listOf(
            "state.js",
            "staleness.js",
            "pendingPersistence.js",
            "beforeunloadGuard.js",
            "undoRedo.js",
            "undoKeymatch.js",
            "previewStaleness.js",
            "activityDrift.js",
            "api.js",
            "connection.js",
            "availability.js",
            "devices.js",
            "preview.js",
            "annotations.js",
            "history.js",
            "prompt.js",
            "rendering.js",
            "sessions-polling.js",
            "shortcuts.js",
            "main.js",
        )
        val expected = modules.joinToString("\n") { name ->
            val source = File(sourceDir, name)
            assertTrue(source.isFile, "Expected console source module $name")
            "// $name\n${source.readText().trimEnd()}\n"
        }
        val generated = File(root, "fixthis-mcp/src/main/resources/console/app.js").readText()

        // Strip the dynamic build header (injected between state.js and api.js) before comparing.
        // The header entry has the form: // build-header\nconst ConsoleBuildEpochMs = N;\nconst ConsoleBuildGitSha = 'X';\n
        // After join('\n') the seam between state.js and the header adds one more \n, giving \n\n before api.js.
        val headerRegex = Regex(
            "// build-header\\nconst ConsoleBuildEpochMs = \\d+;\\nconst ConsoleBuildGitSha = '[a-z0-9]+';\\n\\n",
        )
        val withoutHeader = generated.replace(headerRegex, "")
        assertEquals(expected, withoutHeader)
    }

    @Test
    fun consoleBundleEmbedsBuildEpochAndGitSha() {
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(html.contains("const ConsoleBuildEpochMs = "), "must embed build epoch")
        assertTrue(html.contains("const ConsoleBuildGitSha = '"), "must embed git sha")
    }

    @Test
    fun stalenessModuleExposesCheckAndRender() {
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(html.contains("const StaleThresholdMs"), "threshold const must exist")
        assertTrue(html.contains("async function checkServerStaleness"), "check function must exist")
        assertTrue(html.contains("function renderStalenessBanner"), "render function must exist")
    }

    @Test
    fun stalenessCheckHandlesMissingEndpoint() {
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "checkServerStaleness")
        // 404 = stale signal; 5xx and network errors = silent
        assertTrue(
            body.contains("resp.status === 404") || body.contains("!resp.ok"),
            "must treat 404 as stale signal",
        )
    }

    @Test
    fun servesConsoleAssetsFromConfiguredDirectoryWithoutCaching() {
        val assetsDir = Files.createTempDirectory("fixthis-console-assets").toFile()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        writeConsoleAssets(assetsDir, marker = "first-marker")
        val server = FeedbackConsoleServer(service = service, port = 0, consoleAssetsDir = assetsDir)
        server.start()
        try {
            assertTrue(ConsoleHttpTestClient(server.url).get().contains("first-marker"))

            writeConsoleAssets(assetsDir, marker = "second-marker")

            val refreshedHtml = ConsoleHttpTestClient(server.url).get()
            assertFalse(refreshedHtml.contains("first-marker"))
            assertTrue(refreshedHtml.contains("second-marker"))
        } finally {
            server.stop()
            assetsDir.deleteRecursively()
        }
    }

    @Test
    fun consoleAssetsRejectTraversalPaths() {
        val error = assertFailsWith<IllegalArgumentException> {
            FeedbackConsoleAssets.resource("../FeedbackConsoleAssets.kt")
        }

        assertTrue(error.message!!.contains("path traversal"))
    }

    @Test
    fun consoleAssetsRejectAbsolutePaths() {
        val error = assertFailsWith<IllegalArgumentException> {
            FeedbackConsoleAssets.resource("/index.html")
        }

        assertTrue(error.message!!.contains("absolute asset paths"))
    }

    @Test
    fun consoleHtmlIncludesSessionPickerControls() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("id=\"sessions\""))
        assertTrue(html.contains("/api/sessions"))
        assertTrue(html.contains("/api/session/open"))
        assertTrue(html.contains("state.session = null;"))
    }

    @Test
    fun consoleHtmlOmitsToolbarNavigationControls() {
        val html = FeedbackConsoleAssets.indexHtml

        assertFalse(html.contains("id=\"backButton\""))
        assertFalse(html.contains("id=\"captureAfterNavigation\""))
        assertFalse(html.contains("aria-label=\"Swipe up\""))
        assertTrue(html.contains("/api/navigation"))
        assertTrue(html.contains("captureAfter: false"))
    }

    @Test
    fun consoleHtmlUsesBrowserStudioLayout() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("class=\"studio-shell\""))
        assertTrue(html.contains("class=\"studio-topbar\""))
        assertTrue(html.contains("class=\"studio-history\""))
        assertTrue(html.contains("class=\"studio-canvas\""))
        assertTrue(html.contains("class=\"studio-inspector\""))
        assertFalse(html.contains("id=\"previewModeBadge\""))
        assertTrue(html.contains("id=\"canvasToolbar\""))
        assertTrue(html.contains("id=\"inspectorTitle\""))
        assertTrue(html.contains("id=\"inspectorBody\""))
        assertTrue(html.contains("id=\"inspectorFooter\""))
        assertTrue(html.contains("<div class=\"panel-title\">History</div>"))
        assertTrue(html.contains("--bg-0: #0d0e10"))
        assertTrue(html.contains("--accent: #b8d36a"))
        assertFalse(html.contains("class=\"queue-pane\""))
    }

    @Test
    fun consoleHtmlKeepsStudioUsableInNarrowBrowser() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("@media (max-width: 899px)"))
        assertTrue(Regex("\\.studio-body \\{\\s+grid-template-columns: 1fr;").containsMatchIn(html))
        assertTrue(Regex("\\.studio-history \\{\\s+max-height: 180px;").containsMatchIn(html))
        assertTrue(Regex("\\.studio-inspector \\{\\s+min-height: 280px;").containsMatchIn(html))
        assertTrue(Regex("\\.snapshot-stage \\{\\s+min-height: 360px;").containsMatchIn(html))
        assertFalse(html.contains("Resize to >= 900px wide"))
        assertFalse(html.contains(".studio-shell::before"))
    }

    @Test
    fun consoleHtmlUsesModeAwareStudioInspector() {
        val html = FeedbackConsoleAssets.indexHtml
        val pendingRenderer = javascriptFunctionBody(html, "renderPendingItems")
        val createAnnotationFromSelection = javascriptFunctionBody(html, "createAnnotationFromSelection")
        val renderSavedEvidenceGroups = javascriptFunctionBody(html, "renderSavedEvidenceGroups")

        assertTrue(html.contains("function renderComposerInspector"))
        assertTrue(html.contains("function renderSavedAnnotationsInspector"))
        assertTrue(html.contains("function renderAnnotationDetail"))
        assertTrue(html.contains("function renderSavedAnnotationDetail"))
        assertTrue(html.contains("function colorWithAlpha(color, alpha)"))
        assertTrue(html.contains("box.style.setProperty('--selection-color', color);"))
        assertTrue(html.contains("label.style.setProperty('--selection-color', color);"))
        assertTrue(html.contains("inspectorTitle.textContent = item ? 'Annotation' : 'Annotations'"))
        assertTrue(html.contains("inspectorTitle.textContent = 'Annotations'"))
        assertFalse(html.contains("inspectorTitle.textContent = 'Draft'"))
        assertFalse(html.contains(".saved-evidence-frame .selection-overlay"))
        assertFalse(html.contains("function hydrateSavedEvidencePreviews()"))
        assertTrue(html.contains("background: var(--selection-fill, rgba(184, 211, 106, .12));"))
        assertTrue(html.contains("background: var(--selection-color, var(--accent));"))
        assertTrue(html.contains("box-shadow: inset 3px 0 0 var(--annotation-color, var(--warning));"))
        assertTrue(html.contains("No annotations yet."))
        assertTrue(html.contains("No saved annotations yet."))
        assertTrue(html.contains("Use <b>Annotate</b>"))
        assertTrue(pendingRenderer.contains("ann-row"))
        assertTrue(pendingRenderer.contains("ann-row-num"))
        assertTrue(pendingRenderer.contains("ann-row-status"))
        assertTrue(pendingRenderer.contains("startAnnotatingButtonHtml()"))
        assertTrue(pendingRenderer.contains("data-focus-pending"))
        assertTrue(createAnnotationFromSelection.contains("focusedPendingItemIndex = pendingFeedbackItems.length - 1;"))
        assertFalse(pendingRenderer.contains("data-delete-pending"))
        assertTrue(renderSavedEvidenceGroups.contains("data-focus-saved"))
        assertTrue(html.contains("grid-template-columns: 28px minmax(0, 1fr) auto;"))
        assertTrue(Regex("\\.ann-row-body \\{\\s+min-width: 0;\\s+overflow: hidden;").containsMatchIn(html))
        assertTrue(Regex("\\.ann-row-title \\{\\s+display: block;\\s+max-width: 100%;").containsMatchIn(html))
        assertTrue(Regex("\\.ann-row-status \\{\\s+justify-self: end;").containsMatchIn(html))
        assertTrue(pendingRenderer.contains("style=\"--annotation-color:"))
        assertTrue(html.contains("renderOverlayBox(overlay, image, item.bounds, String(index + 1), false, index === focusedPendingItemIndex, index, '', severityColor(annotationSeverity(item)))"))
        assertTrue(html.contains("focusSavedEvidenceItem(item.itemId)"))
        assertFalse(html.contains("item.bounds, '#' + (index + 1)"))
        assertFalse(html.contains("boundsForTarget(item.target), '#' + (index + 1)"))
        assertTrue(html.contains("<label for=\"annotationLabelInput\">Label</label>"))
        assertTrue(html.contains("<label>Severity</label>"))
        assertTrue(html.contains("<label>Status</label>"))
        assertTrue(html.contains("data-set-severity"))
        assertTrue(html.contains("data-set-status"))
        assertTrue(html.contains("data-delete-current"))
        val annotationActionCss = html.substringAfter(".annotation-actions {").substringBefore("img {")
        assertTrue(annotationActionCss.contains(".annotation-danger:focus-visible"))
        assertTrue(annotationActionCss.contains(".annotation-done:focus-visible"))
        assertTrue(annotationActionCss.contains("border: 1px solid transparent;"))
        assertTrue(annotationActionCss.contains("background: rgba(255, 111, 111, .08);"))
        assertTrue(annotationActionCss.contains("border-color: var(--line);"))
    }

    @Test
    fun consoleHtmlEditsSelectedAnnotationsAndFocusesComment() {
        val html = FeedbackConsoleAssets.indexHtml
        val toolbarAnnotationCounts = javascriptFunctionBody(html, "toolbarAnnotationCounts")
        val renderAnnotationDetail = javascriptFunctionBody(html, "renderAnnotationDetail")
        val renderSavedAnnotationDetail = javascriptFunctionBody(html, "renderSavedAnnotationDetail")
        val persistSavedEvidenceItem = javascriptFunctionBody(html, "persistSavedEvidenceItem")

        assertTrue(toolbarAnnotationCounts.contains("const annotations = toolbarAnnotations();"))
        assertFalse(toolbarAnnotationCounts.contains("const summary = selectedHistorySummary();"))
        assertTrue(renderAnnotationDetail.contains("commentInput.focus();"))
        assertTrue(renderSavedAnnotationDetail.contains("id=\"annotationCommentInput\""))
        assertFalse(renderSavedAnnotationDetail.contains("readonly"))
        assertTrue(renderSavedAnnotationDetail.contains("const editSessionId = focusedSavedSessionId || state.session?.sessionId || null;"))
        assertTrue(renderSavedAnnotationDetail.contains("persistSavedEvidenceItem(item, editSessionId)"))
        assertTrue(persistSavedEvidenceItem.contains("requestJson('/api/items/' + encodeURIComponent(item.itemId)"))
        assertTrue(persistSavedEvidenceItem.contains("method: 'PUT'"))
        assertTrue(persistSavedEvidenceItem.contains("sessionId: sessionId"))
        assertTrue(renderSavedAnnotationDetail.contains("commentInput.focus();"))
    }

    @Test
    fun consoleHtmlResetsAnnotationComposerStateAcrossSessionActions() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("function resetAnnotationComposerState(clearFlow = true)"))
        assertTrue(Regex("async function openSession\\(sessionId\\)[\\s\\S]*resetAnnotationComposerState\\(\\);[\\s\\S]*/api/session/open").containsMatchIn(html))
        assertTrue(Regex("async function newSession\\(\\)[\\s\\S]*resetAnnotationComposerState\\(\\);[\\s\\S]*/api/session/open").containsMatchIn(html))
        assertTrue(Regex("async function closeSession\\(\\)[\\s\\S]*resetAnnotationComposerState\\(\\);[\\s\\S]*/api/session/close").containsMatchIn(html))
        assertTrue(Regex("async function deleteHistorySession\\(sessionId\\)[\\s\\S]*const isDisplayedSession = \\(\\) => state\\.session\\?\\.sessionId === sessionId;[\\s\\S]*if \\(isDisplayedSession\\(\\)\\) \\{\\s+resetAnnotationComposerState\\(\\);").containsMatchIn(html))
        assertTrue(Regex("async function deleteHistorySession\\(sessionId\\)[\\s\\S]*if \\(isDisplayedSession\\(\\)\\) \\{[\\s\\S]*state\\.session = null;[\\s\\S]*await refreshSessions\\(\\);\\s+render\\(\\);\\s+await refreshDevices\\(\\);").containsMatchIn(html))
        assertTrue(Regex("function cancelAddItemsFlow\\(\\)[\\s\\S]*resetAnnotationComposerState\\(\\);[\\s\\S]*render\\(\\);").containsMatchIn(html))
        assertTrue(Regex("function deletePendingFeedbackItem\\(index\\)[\\s\\S]*focusedPendingItemIndex = null;[\\s\\S]*currentSelection = null;[\\s\\S]*comment.value = '';").containsMatchIn(html))
    }

    @Test
    fun consoleHtmlKeepsFixThisTopLevelActionsInStudioTopbar() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("id=\"copyPromptButton\""))
        assertTrue(html.contains("id=\"sendAgentButton\""))
        assertTrue(html.contains("id=\"selectToolButton\""))
        assertTrue(html.contains("id=\"annotateToolButton\""))
        assertFalse(html.contains("id=\"refreshButton\""))
        assertFalse(html.contains("id=\"saveButton\""))
        assertFalse(html.contains("id=\"copyMarkdownButton\""))
        assertFalse(html.contains("id=\"sendDraftButton\""))
        assertFalse(html.contains("id=\"newSessionButton\""))
        assertFalse(html.contains("id=\"closeSessionButton\""))
        assertTrue(html.contains("<span>Copy Prompt</span>"))
        assertTrue(html.contains("<span>Save to MCP</span>"))
        assertTrue(html.contains("<span>Select</span>"))
        assertTrue(html.contains("<span>Annotate</span>"))
        assertTrue(html.contains("class=\"button-icon\" aria-hidden=\"true\""))
        assertTrue(html.contains("stroke-dasharray=\"3 3\""))
        assertFalse(html.contains("<span>Refresh</span>"))
        assertFalse(html.contains("<span>Save snapshot</span>"))
        assertFalse(html.contains(">Copy<"))
        assertFalse(html.contains(">Send<"))
        assertFalse(html.contains(">New<"))
        assertFalse(html.contains(">Close<"))
        assertFalse(html.contains("id=\"modeSelect\""))
        assertFalse(html.contains("id=\"modeNavigate\""))
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
    fun consoleHtmlAddsStudioKeyboardAndAccessibilityGuards() {
        val html = FeedbackConsoleAssets.indexHtml
        val inputGuardBody = javascriptFunctionBody(html, "isTextInputFocused")
        val shortcutBody = javascriptFunctionBody(html, "handleGlobalShortcut")

        assertTrue(html.contains("function isTextInputFocused"))
        assertTrue(html.contains("function handleGlobalShortcut"))
        assertTrue(html.contains("function isTextInputFocused(target = document.activeElement)"))
        assertTrue(inputGuardBody.contains("tag === 'SELECT'"))
        assertTrue(shortcutBody.contains("if (event.repeat) return;"))
        assertTrue(shortcutBody.contains("isTextInputFocused(event.target)"))
        assertTrue(html.contains("event.key === 'Escape'"))
        assertTrue(html.contains("event.key.toLowerCase() === 'a'"))
        assertFalse(html.contains("event.key.toLowerCase() === 's'"))
        assertFalse(html.contains("event.key.toLowerCase() === 'n'"))
        assertTrue(html.contains("!event.shiftKey"))
        assertTrue(html.contains("document.addEventListener('keydown', handleGlobalShortcut)"))
        assertTrue(html.contains("role=\"status\" aria-live=\"polite\""))
        assertTrue(html.contains("aria-label=\"FixThis preview\""))
    }

    @Test
    fun consoleHtmlRendersSavedAnnotationPinsForVisibleScreenWithoutFocus() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderSelectionOverlay = javascriptFunctionBody(html, "renderSelectionOverlay")

        // Saved-evidence pins must render whenever a persisted screen is visible — not only
        // after the user clicks an annotation. The legacy gate (`focusedSavedItemId`) hid
        // every pin until focus was set, so users with multiple saved items saw an empty
        // preview by default. Renderer should derive items from `latestScreen()` and filter
        // by `screenId`.
        assertFalse(
            renderSelectionOverlay.contains("if (!addItemsFlow && focusedSavedItemId)"),
            "renderSelectionOverlay should not gate saved pins on focusedSavedItemId",
        )
        assertTrue(
            renderSelectionOverlay.contains("const visibleScreen = latestScreen();"),
            "renderSelectionOverlay should resolve the visible screen via latestScreen()",
        )
        assertTrue(
            renderSelectionOverlay.contains("const visibleUids = visibleScreenNodeUids(visibleScreen);"),
            "renderSelectionOverlay should compute the visible-screen node-uid set once per call",
        )
        assertTrue(
            renderSelectionOverlay.contains("if (nodeUid) return visibleUids.has(nodeUid);"),
            "renderSelectionOverlay should match node-anchored saved items by nodeUid presence on the visible screen",
        )
        assertTrue(
            renderSelectionOverlay.contains("return item.screenId === visibleScreen.screenId;"),
            "renderSelectionOverlay should fall back to screenId equality for area-anchored items without nodeUid",
        )
        assertFalse(
            renderSelectionOverlay.contains("savedEvidenceItems().filter(item => item.screenId === visibleScreen.screenId)"),
            "renderSelectionOverlay should no longer use the legacy single-line screenId-only filter",
        )
    }

    @Test
    fun consoleHtmlRefreshesSessionSummariesAfterSavedItemDeleteOrEdit() {
        val html = FeedbackConsoleAssets.indexHtml
        val deleteSavedEvidenceItem = javascriptFunctionBody(html, "deleteSavedEvidenceItem")
        val applySavedSessionUpdate = javascriptFunctionBody(html, "applySavedSessionUpdate")

        // History sidebar pip counts (open / done / pts) read from state.sessionSummaries,
        // not state.session. Deleting a saved annotation or editing it (status change)
        // updates state.session in place but left sessionSummaries stale, so the active
        // card kept showing the old "1 open" badge after the panel had emptied. Both code
        // paths must call refreshSessions() so the summary cache is rehydrated.
        // Both functions previously already had refreshSessions() in the non-matching
        // (else) branch only. Adding it to the matching branch means the call must appear
        // twice in each body — once per branch.
        val deleteCount = Regex("refreshSessions\\(\\)").findAll(deleteSavedEvidenceItem).count()
        val applyCount = Regex("refreshSessions\\(\\)").findAll(applySavedSessionUpdate).count()
        assertEquals(2, deleteCount, "deleteSavedEvidenceItem should call refreshSessions() in both branches")
        assertEquals(2, applyCount, "applySavedSessionUpdate should call refreshSessions() in both branches")
    }

    @Test
    fun consoleHtmlReplacesPlaceholderYouLabelWithScreensCount() {
        val html = FeedbackConsoleAssets.indexHtml
        val formatSessionSummary = javascriptFunctionBody(html, "formatSessionSummary")

        // History cards previously showed "You · May 9 · 19:33" — meaningless on a
        // single-user tool. Replaced with the session's screensCount so users can scan
        // sessions by their actual size: e.g. "3 screens · May 9 · 19:33". Sessions
        // without any captured screen drop the prefix entirely instead of showing "0".
        assertFalse(formatSessionSummary.contains("'You · '"))
        assertTrue(formatSessionSummary.contains("session?.screensCount"))
        assertTrue(formatSessionSummary.contains("countLabel(screens, 'screen', 'screens')"))
        assertTrue(formatSessionSummary.contains("screens > 0"))
    }

    @Test
    fun consoleHtmlGroupsSavedAnnotationsByScreenInPanel() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderSavedEvidenceGroups = javascriptFunctionBody(html, "renderSavedEvidenceGroups")

        // The saved-annotations panel previously rendered a flat list of "1, 2, 3, 4" rows
        // even when items were spread across multiple captured screens, so users with more
        // than one screen could not tell which annotation belonged to which screen. The
        // renderer must now emit a "Screen N · HH:MM" header + separator at every screen
        // boundary, using each screen's capture order to pick the ordinal.
        assertTrue(
            html.contains("ann-screen-header"),
            "Bundle should reference the ann-screen-header marker",
        )
        assertTrue(
            renderSavedEvidenceGroups.contains("savedScreenOrdinalLookup()"),
            "renderSavedEvidenceGroups should derive a screen ordinal lookup",
        )
        assertTrue(
            renderSavedEvidenceGroups.contains("if (item.screenId !== prevScreenId)"),
            "renderSavedEvidenceGroups should compare adjacent items' screenId",
        )
        assertTrue(
            renderSavedEvidenceGroups.contains("savedScreenHeaderHtml(item, ordinalByScreenId, prevScreenId === null)"),
            "renderSavedEvidenceGroups should emit a header on every screen-id boundary",
        )
        assertTrue(
            html.contains("function savedScreenOrdinalLookup"),
            "savedScreenOrdinalLookup helper should be defined",
        )
        assertTrue(
            html.contains("function savedScreenHeaderHtml"),
            "savedScreenHeaderHtml helper should be defined",
        )
        assertTrue(
            html.contains(".ann-screen-header {"),
            "styles.css should style the new screen header",
        )
        assertTrue(
            html.contains(".ann-screen-header.first {"),
            "styles.css should suppress the divider above the first screen header",
        )
    }

    @Test
    fun consoleHtmlComposerInspectorAlsoShowsSavedAnnotations() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderComposerInspector = javascriptFunctionBody(html, "renderComposerInspector")

        // While `addItemsFlow` is active the composer inspector previously hid every saved
        // annotation, so users adding a new pin to a session that already had four saved
        // items only saw the single pending entry. The composer must surface saved
        // annotations as well so totals stay coherent across pending + saved.
        assertTrue(
            renderComposerInspector.contains("const savedItems = savedEvidenceItems();"),
            "renderComposerInspector should resolve saved items",
        )
        assertTrue(
            renderComposerInspector.contains("inspectorCount.textContent = String(pendingFeedbackItems.length + savedItems.length);"),
            "renderComposerInspector inspector count should include saved items",
        )
        assertTrue(
            renderComposerInspector.contains("draftItems.hidden = savedItems.length === 0;"),
            "renderComposerInspector should show the saved-items section when savedItems exist",
        )
        assertTrue(
            renderComposerInspector.contains("if (savedItems.length) renderSavedEvidenceGroups();"),
            "renderComposerInspector should populate the saved-items list",
        )
    }

    @Test
    fun consoleHtmlNoLongerFiltersSentItemsFromInspector() {
        val html = FeedbackConsoleAssets.indexHtml
        // Narrow scope: latestPersistedScreen() must include SENT items.
        // The send-path filter inside currentPromptAnnotations() is intentional and stays.
        val latestPersistedScreenBody = javascriptFunctionBody(html, "latestPersistedScreen")
        assertFalse(
            latestPersistedScreenBody.contains("delivery !== 'sent'"),
            "latestPersistedScreen must show SENT items too",
        )
    }

    private fun javascriptFunctionBody(html: String, functionName: String): String {
        val declarationStart = html.indexOf("function $functionName(")
        assertTrue(declarationStart >= 0, "Missing JavaScript function: $functionName")

        val parametersEnd = html.indexOf(')', declarationStart)
        assertTrue(parametersEnd >= 0, "Missing JavaScript function parameter list: $functionName")

        val bodyStart = html.indexOf('{', parametersEnd)
        assertTrue(bodyStart >= 0, "Missing JavaScript function body: $functionName")

        var depth = 1
        for (index in bodyStart + 1 until html.length) {
            when (html[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(bodyStart + 1, index)
                }
            }
        }

        throw AssertionError("Unclosed JavaScript function body: $functionName")
    }

    private fun assertDoesNotClearDraftOrPreview(functionName: String, body: String) {
        assertFalse(
            body.contains("pendingFeedbackItems = [];"),
            "$functionName should not clear pending feedback items",
        )
        assertFalse(
            body.contains("addItemsFlow = null"),
            "$functionName should not clear an active add-items flow",
        )
        assertFalse(
            body.contains("state.preview = null"),
            "$functionName should not clear the current preview",
        )
        assertFalse(
            body.contains("invalidatePreviewContext()"),
            "$functionName should not invalidate preview context",
        )
    }

    private fun writeConsoleAssets(directory: File, marker: String) {
        File(directory, "index.html").writeText(
            """
            <html>
              <head><!-- FIXTHIS_STYLES --></head>
              <body>$marker<!-- FIXTHIS_SCRIPT --></body>
            </html>
            """.trimIndent(),
        )
        File(directory, "styles.css").writeText("body { --marker: '$marker'; }")
        File(directory, "app.js").writeText("window.fixThisMarker = '$marker';")
    }

    @Test
    fun consoleHtmlIncludesSelectionHandoffWorkspace() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("id=\"deviceControl\""))
        assertTrue(html.contains("id=\"devicePicker\""))
        assertTrue(html.contains("id=\"deviceName\""))
        assertTrue(html.contains("id=\"deviceConnectionState\""))
        assertTrue(html.contains("id=\"refreshDevicesButton\""))
        assertTrue(html.contains("id=\"disconnectDeviceButton\""))
        assertTrue(html.contains("aria-label=\"Android device\""))
        assertTrue(html.contains("aria-label=\"Refresh devices\""))
        assertTrue(html.contains("title=\"Refresh devices\""))
        assertTrue(html.contains("Clear selection"))
        assertTrue(html.contains("aria-label=\"Clear FixThis device selection\""))
        assertTrue(html.contains("title=\"Clear FixThis device selection\""))
        assertTrue(html.contains("/api/devices"))
        assertTrue(html.contains("/api/device/select"))
        assertTrue(html.contains("/api/device/disconnect"))
        assertTrue(html.contains("function refreshDevices"))
        assertTrue(html.contains("function selectDevice"))
        assertTrue(html.contains("function disconnectDevice"))
        assertTrue(html.contains("function deviceLabel"))
        assertTrue(html.contains("function shortenDeviceSerial"))
        assertTrue(html.contains("function setDeviceUiState"))
        assertTrue(html.contains("function deviceOptionLabel"))
        assertTrue(html.contains("option.textContent = deviceOptionLabel(device);"))
        assertFalse(html.contains("deviceStatus.textContent = selected ? 'Selected ' + selected.serial"))
        assertFalse(html.contains("Selected ' + selected.serial"))
        assertTrue(html.contains("id=\"previewIntervalSelect\""))
        assertTrue(html.contains("id=\"selectionOverlay\""))
        assertTrue(html.contains("id=\"selectionSummary\""))
        assertTrue(html.contains("id=\"pendingItems\""))
        assertTrue(html.contains("id=\"draftItems\""))
        assertFalse(html.contains("id=\"sentHistory\""))
        assertTrue(html.contains("id=\"sendAgentButton\""))
        assertTrue(html.contains("id=\"copyPromptButton\""))
        assertTrue(html.contains("id=\"clearSelectionButton\""))
        assertTrue(html.contains("id=\"clearDraftButton\""))
        assertTrue(html.contains("/api/preview"))
        assertTrue(html.contains("/api/items/batch"))
        assertTrue(html.contains("/api/items/draft"))
        assertTrue(html.contains("/api/agent-handoffs"))
        assertTrue(html.contains("function clearSelection"))
        assertTrue(html.contains("function clearDraft"))
        assertTrue(html.contains("function sendAgentPrompt"))
        assertTrue(html.contains("selectionSummary.textContent = currentSelection"))
        assertTrue(html.contains("sendAgentButton.disabled = promptDisabled;"))
        assertTrue(html.contains("formatSessionLabel"))
        assertTrue(html.contains("formatSessionSummary"))
        assertTrue(html.contains("historyOpenCount"))
        assertTrue(html.contains("historyDoneCount"))
        assertTrue(html.contains("renderHistoryStrip"))
        assertTrue(html.contains("formatItemLabel"))
        assertTrue(html.contains("function findScreen"))
        assertTrue(html.contains("function targetLabel"))
        assertTrue(html.contains("function sourceHintLabel"))
        assertTrue(html.contains("function escapeHtmlValue(value)"))
        assertTrue(html.contains("escapeHtmlValue(item.comment)"))
        assertFalse(html.contains("id=\"modeSelect\""))
        assertFalse(html.contains("id=\"modeNavigate\""))
    }

    @Test
    fun consoleHtmlShowsReadableDeviceConnectionStates() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("const DeviceUiState = {"))
        assertTrue(html.contains("NONE: 'none'"))
        assertTrue(html.contains("CONNECTING: 'connecting'"))
        assertTrue(html.contains("CONNECTED: 'connected'"))
        assertTrue(html.contains("UNAVAILABLE: 'unavailable'"))
        assertTrue(html.contains("DeviceStateCopy = {"))
        assertTrue(html.contains("No device"))
        assertTrue(html.contains("Connecting"))
        assertTrue(html.contains("Connected"))
        assertTrue(html.contains("Unavailable"))
        assertTrue(html.contains("data-connection-state=\"none\""))
        assertTrue(html.contains("deviceControl.dataset.connectionState = uiState;"))
        assertTrue(html.contains("deviceConnectionState.textContent = decorateConnectionLabel(baseLabel, reason);"))
        assertTrue(html.contains("connection: {"))
        assertTrue(html.contains("hasEverConnected: false"))
        assertTrue(html.contains("lastReadyAt: null"))
        assertTrue(html.contains("launchInFlight: false"))
        assertTrue(html.contains("state.devices = devices;"))
        assertTrue(html.contains("setDeviceUiState(DeviceUiState.CONNECTING, deviceBySerial(state.devices, option.value));"))
    }

    @Test
    fun consoleHtmlRefreshesConnectionStatusWhileDeviceIsSelected() {
        val html = FeedbackConsoleAssets.indexHtml
        val startHeartbeatPolling = javascriptFunctionBody(html, "startHeartbeatPolling")
        val stopHeartbeatPolling = javascriptFunctionBody(html, "stopHeartbeatPolling")
        val sendBridgeHeartbeat = javascriptFunctionBody(html, "sendBridgeHeartbeat")

        assertTrue(html.contains("let heartbeatTimer = null;"))
        assertTrue(html.contains("let heartbeatPolling = false;"))
        assertTrue(sendBridgeHeartbeat.contains("refreshConnection()"))
        assertTrue(sendBridgeHeartbeat.contains("if (!state.session || !state.selectedDeviceSerial) return;"))
        assertTrue(startHeartbeatPolling.contains("sendBridgeHeartbeat()"))
        assertTrue(startHeartbeatPolling.contains("heartbeatPolling = true"))
        assertTrue(startHeartbeatPolling.contains("scheduleNextHeartbeat"))
        assertTrue(stopHeartbeatPolling.contains("heartbeatPolling = false"))
        assertTrue(stopHeartbeatPolling.contains("clearTimeout(heartbeatTimer)"))
        assertTrue(html.contains("unresponsiveTracker.nextBackoffMs()"))
        assertTrue(html.contains("startHeartbeatPolling();"))
        assertTrue(html.contains("stopHeartbeatPolling();"))
    }

    @Test
    fun consoleHtmlDoesNotAutoCapturePreviewWithoutActiveSession() {
        val html = FeedbackConsoleAssets.indexHtml
        val shouldPollPreview = javascriptFunctionBody(html, "shouldPollPreview")
        val captureScreen = javascriptFunctionBody(html, "captureScreen")
        val selectDevice = javascriptFunctionBody(html, "selectDevice")

        assertTrue(shouldPollPreview.contains("Boolean(state.session)"))
        assertTrue(captureScreen.contains("if (!state.session) return;"))
        assertTrue(selectDevice.contains("if (state.session && userConnectionState(state.connection.current) === 'ready')"))
    }

    @Test
    fun consoleHasSimpleConnectionRecoveryCard() {
        val html = FeedbackConsoleAssets.indexHtml
        val refreshConnectionBody = javascriptFunctionBody(html, "refreshConnection")
        val renderConnectionBody = javascriptFunctionBody(html, "renderConnection")
        val launchAppBody = javascriptFunctionBody(html, "launchApp")

        assertTrue(html.contains("id=\"connectionCard\""))
        assertTrue(html.contains("id=\"connectionHeadline\""))
        assertTrue(html.contains("id=\"connectionMessage\""))
        assertTrue(html.contains("id=\"connectionPrimaryAction\""))
        assertTrue(html.contains("id=\"connectionDetails\""))
        assertTrue(html.contains("id=\"connectionDetailsBody\""))
        assertTrue(html.contains("id=\"previewStaleBadge\""))
        assertTrue(html.contains("/api/connection"))
        assertTrue(html.contains("/api/app/launch"))
        assertTrue(refreshConnectionBody.contains("requestJson('/api/connection'"))
        assertTrue(renderConnectionBody.contains("connectionCard.dataset.connectionState"))
        assertTrue(renderConnectionBody.contains("state.connection.hasEverConnected"))
        assertTrue(renderConnectionBody.contains("connectionPrimaryAction.disabled = state.connection.launchInFlight;"))
        assertFalse(renderConnectionBody.contains("connectionPrimaryAction.disabled = state.connection.launchInFlight || viewState === 'starting';"))
        assertTrue(launchAppBody.contains("requestJson('/api/app/launch'"))
    }

    @Test
    fun connectionDropPreservesDraftWorkAndMarksPreviewStale() {
        val html = FeedbackConsoleAssets.indexHtml
        val applyConnectionBody = javascriptFunctionBody(html, "applyConnectionStatus")

        assertTrue(applyConnectionBody.contains("pendingFeedbackItems"))
        assertTrue(applyConnectionBody.contains("markPreviewStale"))
        assertTrue(applyConnectionBody.contains("stopLivePreviewPolling"))
        assertTrue(applyConnectionBody.contains("startLivePreviewPolling"))
        assertTrue(applyConnectionBody.contains("state.connection.hasEverConnected = true"))
        assertDoesNotClearDraftOrPreview("applyConnectionStatus", applyConnectionBody)
    }

    @Test
    fun previewFailureMarksConnectionPausedWithoutClearingDrafts() {
        val html = FeedbackConsoleAssets.indexHtml
        val refreshPreviewBody = javascriptFunctionBody(html, "refreshPreview")
        val refreshConnectionBody = javascriptFunctionBody(html, "refreshConnection")
        val friendlyErrorMessageBody = javascriptFunctionBody(html, "friendlyErrorMessage")
        val showErrorBody = javascriptFunctionBody(html, "showError")
        val sendBridgeHeartbeatBody = javascriptFunctionBody(html, "sendBridgeHeartbeat")
        val applyConnectionBody = javascriptFunctionBody(html, "applyConnectionStatus")

        assertTrue(refreshPreviewBody.contains("markPreviewStale(true)"))
        assertTrue(refreshPreviewBody.contains("refreshConnection({ preservePreviewStale: true }).catch"))
        assertTrue(refreshConnectionBody.contains("applyConnectionStatus(status, options);"))
        assertTrue(applyConnectionBody.contains("const connectionOptions = options || {};"))
        assertTrue(applyConnectionBody.contains("if (!connectionOptions.preservePreviewStale) markPreviewStale(false);"))
        assertTrue(friendlyErrorMessageBody.contains("Connection paused. Your work is saved."))
        val friendlyReturnIndex = friendlyErrorMessageBody.indexOf("return 'Connection paused. Your work is saved.';")
        assertTrue(friendlyReturnIndex >= 0)
        assertTrue(
            friendlyErrorMessageBody.indexOf("Bridge closed before sending a response") in 0 until friendlyReturnIndex,
            "Bridge closed failures should map to the saved-work message",
        )
        assertTrue(
            friendlyErrorMessageBody.indexOf("Could not connect to FixThis bridge") in 0 until friendlyReturnIndex,
            "Bridge connection failures should map to the saved-work message",
        )
        assertTrue(
            friendlyErrorMessageBody.indexOf("lower.includes('bridge')") in 0 until friendlyReturnIndex &&
                friendlyErrorMessageBody.indexOf("lower.includes('timed out')") in 0 until friendlyReturnIndex,
            "Only bridge-specific timeout failures should map to the saved-work message",
        )
        assertFalse(
            friendlyErrorMessageBody.contains("raw.includes('timed out')"),
            "Unrelated timeout failures should keep their original error text",
        )
        assertTrue(friendlyErrorMessageBody.contains("DEVICE_NOT_AVAILABLE"))
        assertTrue(friendlyErrorMessageBody.contains("Check your phone, then try again."))
        assertTrue(showErrorBody.contains("friendlyErrorMessage"))
        assertTrue(html.contains("pendingFeedbackItems = [];"))
        assertDoesNotClearDraftOrPreview("refreshPreview", refreshPreviewBody)
        assertDoesNotClearDraftOrPreview("friendlyErrorMessage", friendlyErrorMessageBody)
        assertDoesNotClearDraftOrPreview("showError", showErrorBody)
        assertDoesNotClearDraftOrPreview("sendBridgeHeartbeat", sendBridgeHeartbeatBody)
        assertDoesNotClearDraftOrPreview("applyConnectionStatus", applyConnectionBody)
    }

    @Test
    fun readyConnectionSyncsSelectedDeviceBeforePreviewPolling() {
        val html = FeedbackConsoleAssets.indexHtml
        val applyConnectionBody = javascriptFunctionBody(html, "applyConnectionStatus")
        val syncSelectedDeviceBody = javascriptFunctionBody(html, "syncSelectedDeviceFromConnection")

        assertTrue(syncSelectedDeviceBody.contains("const selectedDevice = status?.selectedDevice;"))
        assertTrue(syncSelectedDeviceBody.contains("selectedDevice?.serial"))
        assertTrue(syncSelectedDeviceBody.contains("state.selectedDeviceSerial = selectedDevice.serial;"))
        assertTrue(syncSelectedDeviceBody.contains("deviceBySerial(state.devices, selectedDevice.serial)"))
        assertTrue(syncSelectedDeviceBody.contains("setDeviceUiState"))

        val syncIndex = applyConnectionBody.indexOf("syncSelectedDeviceFromConnection(status);")
        val pollingIndex = applyConnectionBody.indexOf("startLivePreviewPolling();")
        assertTrue(syncIndex >= 0, "Connection status should sync server-selected device")
        assertTrue(pollingIndex >= 0, "Ready connection should start live preview polling")
        assertTrue(syncIndex < pollingIndex, "Selected device must be synced before preview polling starts")
    }

    @Test
    fun heartbeatApiPingsBridgeStatusWithoutCapturingPreview() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/heartbeat")

            assertEquals(200, connection.responseCode)
            assertEquals(1, bridge.statusCount)
            assertEquals(0, bridge.captureCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun connectionApiReturnsSimpleConnectionStatus() {
        val bridge = FakeFixThisBridge()
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/connection")
            val json = fixThisJson.parseToJsonElement(body).jsonObject

            assertEquals("READY", json.getValue("state").jsonPrimitive.content)
            assertEquals("Ready", json.getValue("headline").jsonPrimitive.content)
            assertEquals(true, json.getValue("canCapture").jsonPrimitive.boolean)
        } finally {
            server.stop()
        }
    }

    @Test
    fun connectionStatusSurfacesAvailabilitySignalsFromBridgeStatus() {
        val bridge = FakeFixThisBridge(
            statusProvider = {
                buildJsonObject {
                    put("activity", "MainActivity")
                    put("rootsCount", 3)
                    put("screenInteractive", true)
                    put("keyguardLocked", false)
                    put("appForeground", true)
                    put("pictureInPicture", false)
                }
            },
        )
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/connection")
            val json = fixThisJson.parseToJsonElement(body).jsonObject

            assertEquals("READY", json.getValue("state").jsonPrimitive.content)
            val availability = json.getValue("availability").jsonObject
            assertEquals(true, availability.getValue("screenInteractive").jsonPrimitive.boolean)
            assertEquals(false, availability.getValue("keyguardLocked").jsonPrimitive.boolean)
            assertEquals(true, availability.getValue("appForeground").jsonPrimitive.boolean)
            assertEquals(false, availability.getValue("pictureInPicture").jsonPrimitive.boolean)
            assertEquals(3, availability.getValue("rootsCount").jsonPrimitive.int)
            assertEquals("MainActivity", availability.getValue("activity").jsonPrimitive.content)
        } finally {
            server.stop()
        }
    }

    @Test
    fun connectionStatusOmitsAvailabilityFieldsWhenLegacyBridgeStatusIsMissingThem() {
        val bridge = FakeFixThisBridge(
            statusProvider = {
                buildJsonObject {
                    put("rootsCount", 2)
                    put("sidekickVersion", "0.0.1")
                    put("bridgeProtocolVersion", 1)
                    put("sourceIndexAvailable", true)
                }
            },
        )
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/connection")
            val json = fixThisJson.parseToJsonElement(body).jsonObject

            assertEquals("READY", json.getValue("state").jsonPrimitive.content)
            val availability = json.getValue("availability").jsonObject
            assertEquals(null, availability["screenInteractive"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(null, availability["keyguardLocked"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(null, availability["appForeground"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(null, availability["pictureInPicture"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(2, availability.getValue("rootsCount").jsonPrimitive.int)
            assertEquals(null, availability["activity"]?.jsonPrimitive?.contentOrNull)
        } finally {
            server.stop()
        }
    }

    @Test
    fun connectionStatusDoesNotCreateHiddenSessionAfterHistoryIsCleared() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "session-2").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val client = ConsoleHttpTestClient(server.url)
            val open = client.connection(
                "/api/session/open",
                method = "POST",
                body = """{"newSession":true}""",
            )
            assertEquals(200, open.responseCode)
            open.inputStream.close()

            val close = client.connection(
                "/api/session/close",
                method = "POST",
                body = """{"sessionId":"session-1"}""",
            )
            assertEquals(200, close.responseCode)
            close.inputStream.close()

            val connection = client.connection("/api/connection")
            assertEquals(200, connection.responseCode)
            connection.inputStream.close()

            val sessions = fixThisJson.parseToJsonElement(client.get("/api/sessions")).jsonObject
                .getValue("sessions")
                .jsonArray

            assertEquals(0, sessions.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun appLaunchApiDoesNotCreateHiddenSessionWhenHistoryIsEmpty() {
        val bridge = FakeFixThisBridge()
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val client = ConsoleHttpTestClient(server.url)
            val launch = client.connection("/api/app/launch", method = "POST", body = "{}")

            assertEquals(200, launch.responseCode)
            launch.inputStream.close()
            val sessions = fixThisJson.parseToJsonElement(client.get("/api/sessions")).jsonObject
                .getValue("sessions")
                .jsonArray
            assertEquals(0, sessions.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun heartbeatApiDoesNotCreateHiddenSessionWhenHistoryIsEmpty() {
        val bridge = FakeFixThisBridge()
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val client = ConsoleHttpTestClient(server.url)
            val heartbeat = client.connection("/api/heartbeat")

            assertEquals(200, heartbeat.responseCode)
            heartbeat.inputStream.close()
            val sessions = fixThisJson.parseToJsonElement(client.get("/api/sessions")).jsonObject
                .getValue("sessions")
                .jsonArray
            assertEquals(0, sessions.size)
        } finally {
            server.stop()
        }
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
    fun launchAppApiLaunchesSelectedPackageAndReturnsStartingStatus() {
        val bridge = FakeFixThisBridge(heartbeatError = RuntimeException("not ready yet"))
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/app/launch")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.outputStream.use { it.write(ByteArray(0)) }

            assertEquals(200, connection.responseCode)
            val json = fixThisJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            assertEquals("STARTING", json.getValue("state").jsonPrimitive.content)
            assertEquals(listOf("io.beyondwin.fixthis.sample"), bridge.launchedPackages)
        } finally {
            server.stop()
        }
    }

    @Test
    fun consoleHtmlDisablesPreviewPollingForUnavailableDeviceSelection() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("const selectedSerial = selected && selected.state === 'device' ? selected.serial : null;"))
        assertTrue(html.contains("state.selectedDeviceSerial = null;"))
        assertTrue(html.contains("stopLivePreviewPolling();"))
        assertTrue(html.contains("setDeviceUiState(DeviceUiState.UNAVAILABLE, deviceBySerial(state.devices, option.value) || { serial: option.value });"))
    }

    @Test
    fun consoleHtmlAutoSelectsSingleConnectedDeviceOnRefresh() {
        val html = FeedbackConsoleAssets.indexHtml
        val refreshDevices = javascriptFunctionBody(html, "refreshDevices")

        assertTrue(refreshDevices.contains("let payload = await requestJson('/api/devices');"))
        assertTrue(refreshDevices.contains("const devices = payload.devices || [];"))
        assertTrue(refreshDevices.contains("const connectedDevices = (payload.devices || []).filter(device => device.state === 'device');"))
        assertTrue(refreshDevices.contains("if (!payload.selectedSerial && devices.length === 1 && connectedDevices.length === 1) {"))
        assertTrue(refreshDevices.contains("body: JSON.stringify({ serial: connectedDevices[0].serial })"))
        assertTrue(refreshDevices.contains("renderDeviceList(payload);"))
    }

    @Test
    fun consoleHtmlRerendersPreviewWhenDeviceSelectionInvalidatesPreview() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderDeviceList = javascriptFunctionBody(html, "renderDeviceList")
        val noDevicesSelectionChange = Regex(
            """
            if \(!devices\.length\) \{[\s\S]*?if \(previousSelectedDeviceSerial !== selectedSerial\) \{\s*invalidatePreviewContext\(\);\s*renderPreviewOnly\(\);\s*\}
            """.trimIndent(),
        )
        val selectedSerialChange = Regex(
            """
            const selectedSerial = selected && selected\.state === 'device' \? selected\.serial : null;[\s\S]*?if \(previousSelectedDeviceSerial !== selectedSerial\) \{\s*invalidatePreviewContext\(\);\s*renderPreviewOnly\(\);\s*\}
            """.trimIndent(),
        )

        assertTrue(
            noDevicesSelectionChange.containsMatchIn(renderDeviceList),
            "No-devices selection invalidation must rerender the preview region",
        )
        assertTrue(
            selectedSerialChange.containsMatchIn(renderDeviceList),
            "Selected-serial invalidation must rerender the preview region",
        )
    }

    @Test
    fun consoleHtmlClearsDeviceUiOnlyAfterClearSelectionSucceeds() {
        val html = FeedbackConsoleAssets.indexHtml
        val clearRequest = "renderDeviceList(await requestJson('/api/device/disconnect', { method: 'POST' }));"
        val clearUi = "setDeviceUiState(DeviceUiState.NONE);"
        val clearRequestIndex = html.indexOf(clearRequest)
        val clearUiIndex = if (clearRequestIndex >= 0) html.indexOf(clearUi, clearRequestIndex) else -1

        assertTrue(clearRequestIndex >= 0)
        assertTrue(clearUiIndex > clearRequestIndex)
    }

    @Test
    fun consoleHtmlShortensWifiAdbSerialsForNormalDeviceLabels() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("function shortenDeviceSerial(serial)"))
        assertTrue(html.contains("withoutServiceSuffix = raw.split('._adb-tls-connect._tcp')[0];"))
        assertTrue(html.contains("if (withoutServiceSuffix.startsWith('adb-')) return withoutServiceSuffix.substring(4);"))

        val deviceLabelFallback = Regex("""function deviceLabel\(device\) \{\s+if \(!device\) return 'No device';\s+return ([^;]+);""")
            .find(html)
            ?.groupValues
            ?.get(1)

        assertEquals(
            "device.label || device.model || device.deviceName || device.product || shortenDeviceSerial(device.serial) || 'Unknown device'",
            deviceLabelFallback,
            "Connection-device label must be the first label fallback while preserving normal device serial shortening",
        )
    }

    @Test
    fun consoleHtmlDoesNotRenderInternalIdsInHumanLabels() {
        val html = FeedbackConsoleAssets.indexHtml

        assertFalse(html.contains("id \${shortId(session.sessionId)}"))
        assertFalse(html.contains("\${session.status} | \${shortId(session.sessionId)}"))
        assertFalse(html.contains(" | \${escapeHtml(shortId(screen.screenId))} | "))
        assertFalse(html.contains("item \${escapeHtml(shortId(item.itemId))}"))
        assertFalse(html.contains("screen \${escapeHtml(shortId(item.screenId))}"))
        assertFalse(html.contains("batch \${escapeHtml(shortId(batch.batchId))}"))
        assertFalse(html.contains("items \${escapeHtml((batch.itemIds || []).map(shortId).join(', ') || '-')}"))
        assertFalse(html.contains("Missing item \${shortId(item.itemId)}"))

        assertFalse(html.contains("function formatSessionHeader"))
        assertFalse(html.contains("feedback item', 'feedback items'"))
        assertTrue(html.contains("function renderSavedEvidenceGroups"))
    }

    @Test
    fun consoleHtmlRendersStudioSessionHistoryWithoutInternalIds() {
        val html = FeedbackConsoleAssets.indexHtml
        val formatSessionLabel = javascriptFunctionBody(html, "formatSessionLabel")

        assertTrue(html.contains("function renderSessionsList"))
        assertTrue(html.contains("sessionCount.textContent"))
        assertTrue(html.contains("class=\"history-item session-row"))
        assertTrue(html.contains("class=\"hi-head\""))
        assertTrue(html.contains("class=\"hi-title\""))
        assertTrue(html.contains("class=\"hi-meta\""))
        assertTrue(html.contains("class=\"hi-stats\""))
        assertTrue(html.contains("class=\"hi-strip\""))
        assertTrue(html.contains("class=\"hi-pip open\""))
        assertTrue(html.contains("class=\"hi-pip done\""))
        assertTrue(html.contains("class=\"hi-strip-cell"))
        assertTrue(html.contains("data-delete-session-id"))
        assertTrue(html.contains("async function deleteHistorySession(sessionId)"))
        assertTrue(html.contains("event.stopPropagation();"))
        assertTrue(html.contains("row.addEventListener('keydown'"))
        assertTrue(html.contains("row.classList.toggle('is-active'"))
        assertTrue(html.contains(".history-list { align-content: start; }"))
        assertFalse(html.contains("class=\"sent-history-drawer\""))
        assertTrue(html.contains("formatSessionSummary(session)"))
        assertTrue(html.contains("function sessionOrdinalLookup(sessions)"))
        assertTrue(html.contains("createdAtEpochMillis || 0"))
        assertTrue(html.contains("ordinalBySessionId.set(session.sessionId, index + 1);"))
        assertTrue(html.contains("function stableHistorySessions(sessions)"))
        assertTrue(html.contains("const renderedActiveSummaries = stableHistorySessions(activeSummaries);"))
        assertTrue(formatSessionLabel.contains("const safeOrdinal = Math.max(1, Number(ordinal || 1));"))
        assertTrue(formatSessionLabel.contains("return 'Session ' + safeOrdinal;"))
        assertFalse(formatSessionLabel.contains("state.session"))
        assertFalse(formatSessionLabel.contains("latestScreen"))
        assertFalse(formatSessionLabel.contains("displayName"))
        assertFalse(formatSessionLabel.contains("packageTail"))
        assertFalse(formatSessionLabel.contains("Feedback snapshot"))
        assertTrue(html.contains("const ordinalBySessionId = sessionOrdinalLookup(activeSummaries);"))
        assertTrue(html.contains("const renderedSessions = renderedActiveSummaries.map((session, index) => {"))
        assertTrue(html.contains("const label = formatSessionLabel(session, ordinalBySessionId.get(session.sessionId) || index + 1);"))
        assertTrue(html.contains("max-height:"))
        assertTrue(html.contains("overflow: auto"))
        assertTrue(html.contains("function historyStartAnnotatingItemHtml()"))
        assertTrue(html.contains("class=\"history-item history-add-row\""))
        assertTrue(html.contains("data-start-new-history-annotating"))
        assertTrue(html.contains("function emptySessionsHtml()"))
        assertTrue(Regex("sessions\\.innerHTML = renderedSessions\\s+\\? renderedSessions \\+ historyStartAnnotatingItemHtml\\(\\)\\s+: historyStartAnnotatingItemHtml\\(\\) \\+ emptySessionsHtml\\(\\);").containsMatchIn(html))
        assertTrue(html.contains("button.addEventListener('click', () => enterNewHistoryAnnotateMode().catch(showError));"))
        assertTrue(html.contains("async function enterNewHistoryAnnotateMode()"))
        assertTrue(html.contains("let newHistoryAnnotateModeStarting = false;"))
        assertTrue(html.contains("if (newHistoryAnnotateModeStarting) return;"))
        assertTrue(html.contains("newHistoryAnnotateModeStarting = true;"))
        assertTrue(html.contains("newHistoryAnnotateModeStarting = false;"))
        assertTrue(html.contains("await newSession();"))
        assertTrue(html.contains("scrollActiveHistoryItemIntoView();"))
        assertTrue(html.contains("await enterAnnotateMode();"))
        assertTrue(html.contains("function scrollActiveHistoryItemIntoView()"))
        assertTrue(html.contains("sessions.querySelector('.session-row.is-active')"))
        assertTrue(html.contains("renderCurrentSessionList();"))
        assertTrue(html.contains("if (newHistoryAnnotateModeStarting) return '';"))
        assertFalse(html.contains("if (toolMode === 'annotate' || newHistoryAnnotateModeStarting) return '';"))
        assertFalse(html.contains("id=\"historyStartAnnotatingButton\""))
        assertFalse(html.contains(".panel-head-actions"))
        assertTrue(html.contains(".history-add-row"))
        assertFalse(html.contains("· Not sent"))
        assertFalse(html.contains("shortId(session.sessionId)"))
        assertFalse(html.contains("shortId(screen.screenId)"))
        assertFalse(html.contains("shortId(batch.batchId)"))
    }

    @Test
    fun consoleHtmlFlushesPendingAnnotationsBeforeSessionSwitch() {
        val html = FeedbackConsoleAssets.indexHtml
        val openSession = javascriptFunctionBody(html, "openSession")
        val newSession = javascriptFunctionBody(html, "newSession")
        val flushPending = javascriptFunctionBody(html, "flushPendingAnnotationsBeforeSessionChange")
        val persistPending = javascriptFunctionBody(html, "persistPendingFeedbackItems")
        val pendingPayload = javascriptFunctionBody(html, "pendingPayloadItems")

        assertTrue(html.contains("async function flushPendingAnnotationsBeforeSessionChange()"))
        assertTrue(flushPending.contains("if (!addItemsFlow || !pendingFeedbackItems.length) return;"))
        assertTrue(flushPending.contains("await persistPendingFeedbackItems({ allowBlankComments: true });"))
        assertTrue(openSession.contains("await flushPendingAnnotationsBeforeSessionChange();"))
        assertTrue(newSession.contains("await flushPendingAnnotationsBeforeSessionChange();"))
        assertTrue(html.contains("const allowBlankComments = Boolean(options.allowBlankComments);"))
        assertTrue(html.contains("!allowBlankComments"))
        assertTrue(html.contains("allowBlankComments: allowBlankComments"))
    }

    @Test
    fun consoleUsesOptionASelectAnnotateToolsAndSimpleLabels() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("<span>Select</span>"))
        assertTrue(html.contains("<span>Annotate</span>"))
        assertTrue(html.contains("<span>Copy Prompt</span>"))
        assertTrue(html.contains("<span>Save to MCP</span>"))
        assertFalse(html.contains("<span>Save snapshot</span>"))
        assertFalse(html.contains("<span>Refresh</span>"))
        assertFalse(html.contains(">Copy<"))
        assertFalse(html.contains(">Send<"))
        assertTrue(html.contains("setInterval"))
        assertTrue(html.contains("document.hidden"))
        assertTrue(html.contains("previewIntervalSelect"))
        assertTrue(html.contains("PreviewIntervalStorageKey"))
        assertTrue(html.contains("Math.max(1000"))
        assertTrue(html.contains("let toolMode = 'select'"))
        assertTrue(html.contains("function enterAnnotateMode"))
        assertTrue(html.contains("function enterSelectMode"))
        assertTrue(Regex("async function enterAnnotateMode\\(\\) \\{\\s+await ensureSessionForAnnotating\\(\\);\\s+toolMode = 'annotate';\\s+renderCurrentSessionList\\(\\);\\s+if \\(!addItemsFlow\\) \\{\\s+await startAddItemsFlow\\(\\);").containsMatchIn(html))
        assertTrue(html.contains("inspectorTitle.textContent = item ? 'Annotation' : 'Annotations'"))
        assertTrue(html.contains("pendingItems.hidden = false"))
        assertTrue(html.contains("renderPendingItems"))
        assertTrue(html.contains("renderNumberedFeedbackOverlay"))
        assertTrue(html.contains("focusPendingFeedbackItem"))
        assertTrue(html.contains("deletePendingFeedbackItem"))
        assertTrue(html.contains("renderSavedEvidenceGroups"))
        assertTrue(html.contains("const DefaultLivePreviewIntervalMs = 1000"))
        assertTrue(html.contains("const MinLivePreviewIntervalMs = 1000"))
        assertTrue(html.contains("<option value=\"1000\" selected>1s</option>"))
        assertTrue(html.contains("const PreviewIntervalStorageKey = 'fixthis.previewIntervalMs.v2'"))
        assertFalse(html.contains("id=\"sessionMeta\""))
        assertFalse(html.contains("function formatSessionHeader"))
        assertTrue(html.contains("startAddItemsFlow"))
        assertTrue(html.contains("createAnnotationFromSelection"))
        assertTrue(html.contains("savePendingFeedbackItems"))
        assertFalse(html.contains("modeSelect"))
        assertFalse(html.contains("modeNavigate"))
        assertFalse(html.contains("id=\"addFlowButton\""))
        assertFalse(html.contains("Add to Pending"))
        assertFalse(html.contains("Clear Comment"))
    }

    @Test
    fun consoleHtmlKeepsHiddenInspectorListsOutOfLayout() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("[hidden] { display: none !important; }"))
        assertTrue(html.contains("pendingItems.hidden = true"))
        // Composer inspector now keeps the saved-items list visible whenever the session
        // already has saved annotations (so users don't lose them while adding new ones).
        // The list is hidden via a length check rather than a literal `= true` assignment.
        assertTrue(html.contains("draftItems.hidden = savedItems.length === 0;"))
    }

    @Test
    fun consoleHtmlShowsStartAnnotatingWhenSavedAnnotationsAreEmpty() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderSavedEvidenceGroups = javascriptFunctionBody(html, "renderSavedEvidenceGroups")

        assertTrue(renderSavedEvidenceGroups.contains("startAnnotatingButtonHtml()"))
        assertTrue(html.contains("data-start-annotating"))
        assertTrue(html.contains("Start annotating"))
        assertTrue(html.contains("function bindStartAnnotatingButtons(container)"))
        assertTrue(renderSavedEvidenceGroups.contains("bindStartAnnotatingButtons(draftItems);"))
        assertTrue(html.contains("function startAnnotatingButtonHtml()"))
        assertTrue(html.contains("if (toolMode === 'annotate') return '';"))
        assertTrue(html.contains("function historyStartAnnotatingItemHtml()"))
    }

    @Test
    fun consoleHtmlGivesBackToAnnotationsButtonButtonPadding() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(Regex("\\.annotation-back \\{[\\s\\S]*min-height: 32px;[\\s\\S]*padding: 0 14px;").containsMatchIn(html))
        assertTrue(Regex("\\.annotation-danger,[\\s\\S]*\\.annotation-done \\{[\\s\\S]*padding: 0 14px;").containsMatchIn(html))
    }

    @Test
    fun consoleHtmlPlacesAnnotateHintOutsideDeviceFrame() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderPreviewRegion = javascriptFunctionBody(html, "renderPreviewRegion")

        assertTrue(html.contains(".snapshot-stage"))
        assertTrue(html.contains("flex-direction: column;"))
        assertTrue(html.contains(".annotate-hint-slot"))
        assertTrue(Regex("\\.snapshot-stage \\{[\\s\\S]*gap: 10px;").containsMatchIn(html))
        assertTrue(html.contains(".annotate-hint"))
        assertTrue(html.contains("position: static;"))
        assertTrue(html.contains("id=\"annotateHintSlot\""))
        assertTrue(renderPreviewRegion.contains("snapshot.dataset.toolMode = toolMode;"))
        assertTrue(renderPreviewRegion.contains("const hintSlot = document.getElementById('annotateHintSlot');"))
        assertTrue(renderPreviewRegion.contains("hintSlot.appendChild(hint);"))
        assertFalse(renderPreviewRegion.contains("snapshot.insertBefore(hint, frame);"))
        assertFalse(renderPreviewRegion.contains("frame.appendChild(hint);"))
    }

    @Test
    fun consoleHtmlKeepsPreviewFramePositionStableAcrossSelectAndAnnotateModes() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(Regex("\\.snapshot-stage \\{[\\s\\S]*justify-content: flex-start;[\\s\\S]*padding: 12px 24px 24px;").containsMatchIn(html))
        assertTrue(Regex("\\.annotate-hint-slot \\{[\\s\\S]*min-height: 32px;").containsMatchIn(html))
        assertFalse(Regex("\\.snapshot-stage\\[data-tool-mode=\"annotate\"\\] \\{[\\s\\S]*(justify-content|padding-top)").containsMatchIn(html))
    }

    @Test
    fun consoleHtmlCreatesHistorySessionBeforeAnnotatingFromEmptyState() {
        val html = FeedbackConsoleAssets.indexHtml
        val hasActiveHistorySessionForAnnotating = javascriptFunctionBody(html, "hasActiveHistorySessionForAnnotating")
        val ensureSessionForAnnotating = javascriptFunctionBody(html, "ensureSessionForAnnotating")
        val enterAnnotateMode = javascriptFunctionBody(html, "enterAnnotateMode")

        assertTrue(html.contains("function hasActiveHistorySessionForAnnotating()"))
        assertTrue(ensureSessionForAnnotating.contains("if (hasActiveHistorySessionForAnnotating()) return;"))
        assertTrue(hasActiveHistorySessionForAnnotating.contains("state.session.status !== 'closed'"))
        assertTrue(hasActiveHistorySessionForAnnotating.contains("(state.sessionSummaries || []).some"))
        assertTrue(ensureSessionForAnnotating.contains("/api/session/open"))
        assertTrue(ensureSessionForAnnotating.contains("body: JSON.stringify({ newSession: true })"))
        assertTrue(ensureSessionForAnnotating.contains("await refreshSessions();"))
        assertTrue(enterAnnotateMode.contains("await ensureSessionForAnnotating();"))
        assertTrue(enterAnnotateMode.contains("toolMode = 'annotate';"))
        assertTrue(enterAnnotateMode.contains("renderCurrentSessionList();"))
        assertTrue(enterAnnotateMode.contains("if (!addItemsFlow) {"))
        assertTrue(enterAnnotateMode.contains("await startAddItemsFlow();"))
    }

    @Test
    fun consoleHtmlNoLongerFiltersReadyForAgentSessions() {
        val html = FeedbackConsoleAssets.indexHtml
        val rendered = javascriptFunctionBody(html, "renderSessionsListFromPayload")
        assertFalse(rendered.contains("'ready_for_agent'"), "History list must show sent sessions too")
    }

    @Test
    fun consoleHtmlKeepsFrozenPreviewStableAndShowsPersistedScreenHistory() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("let previewRequestGeneration = 0"))
        assertTrue(html.contains("let previewRequestInFlight = null"))
        assertTrue(html.contains("const preview = await requestLivePreview();"))
        assertTrue(html.contains("const requestGeneration = ++previewRequestGeneration"))
        assertTrue(html.contains("if (addItemsFlow || requestGeneration !== previewRequestGeneration) return;"))
        assertTrue(html.contains("screenshotUrl: previewScreenshotUrl(state.preview.previewId)"))
        assertTrue(html.contains("function latestPersistedScreen()"))
        assertTrue(html.contains("const persistedScreenIds = new Set("))
        assertTrue(html.contains(".filter(screen => persistedScreenIds.has(screen.screenId))"))
        assertTrue(html.contains("function screenImageUrl(screen)"))
        assertTrue(html.contains("function latestScreen()"))
        assertTrue(html.contains("if (addItemsFlow) return addItemsFlow.screen;"))
        assertTrue(html.contains("if (focusedSavedItemId) {"))
        assertTrue(html.contains("return state.preview?.screen || latestPersistedScreen();"))
        assertFalse(html.contains("return addItemsFlow?.screen || latestPersistedScreen() || state.preview?.screen;"))
        assertTrue(html.contains("'/api/screens/' + encodeURIComponent(screen.screenId) + '/screenshot/full'"))
        assertTrue(html.contains("if (!addItemsFlow) {"))
        assertTrue(html.contains("const visibleScreen = latestScreen();"))
        assertTrue(html.contains("const visibleUids = visibleScreenNodeUids(visibleScreen);"))
        assertTrue(html.contains("if (nodeUid) return visibleUids.has(nodeUid);"))
        assertTrue(html.contains("return item.screenId === visibleScreen.screenId;"))
        assertFalse(html.contains("savedEvidenceItems().filter(item => item.screenId === visibleScreen.screenId)"))
        assertTrue(html.contains("if (screenSavedItems.length) renderSavedEvidenceOverlay(overlay, image, screenSavedItems);"))
        assertFalse(html.contains("renderSavedEvidenceOverlay(overlay, image, persistedItems);"))
        assertFalse(html.contains("if (!addItemsFlow && !state.preview && persistedItems.length)"))
        assertTrue(Regex("async function openSession\\(sessionId\\)[\\s\\S]*stopLivePreviewPolling\\(\\);[\\s\\S]*await refresh\\(\\);").containsMatchIn(html))
        assertTrue(html.contains("function savedEvidenceItems()"))
        assertFalse(html.contains("function persistedItemsForScreen(screenId)"))
        assertFalse(html.contains("escapeHtml(formatSavedEvidenceItemLabel(item, index))"))
    }

    @Test
    fun consoleHtmlKeepsSavedAnnotationPreviewsInCenterDeviceOnly() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderSavedEvidenceGroups = javascriptFunctionBody(html, "renderSavedEvidenceGroups")
        val renderSavedEvidenceOverlay = javascriptFunctionBody(html, "renderSavedEvidenceOverlay")
        val renderOverlayBox = javascriptFunctionBody(html, "renderOverlayBox")

        assertTrue(html.contains("function renderSavedEvidenceOverlay(overlay, image, items)"))
        assertTrue(html.contains("let focusedSavedItemId = null"))
        assertTrue(html.contains("function focusSavedEvidenceItem(itemId)"))
        assertTrue(html.contains("function selectedSavedAnnotation()"))
        assertTrue(renderOverlayBox.contains("selectHandler"))
        assertTrue(renderOverlayBox.contains("selectHandler(annotationIndex);"))
        assertTrue(renderSavedEvidenceOverlay.contains("focusSavedEvidenceItem(item.itemId)"))
        assertTrue(html.contains("savedEvidenceItems()"))
        assertFalse(renderSavedEvidenceGroups.contains("saved-evidence-preview"))
        assertFalse(renderSavedEvidenceGroups.contains("hydrateSavedEvidencePreviews"))
        assertFalse(html.contains("function hydrateSavedEvidencePreviews()"))
    }

    @Test
    fun consoleHtmlRendersSavedAnnotationsWithSameListUiAfterSessionSwitch() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderSavedEvidenceGroups = javascriptFunctionBody(html, "renderSavedEvidenceGroups")

        assertTrue(renderSavedEvidenceGroups.contains("const items = savedEvidenceItems();"))
        assertTrue(renderSavedEvidenceGroups.contains("'<div class=\"ann-list\">'"))
        assertTrue(renderSavedEvidenceGroups.contains("class=\"ann-row saved-item-row"))
        assertTrue(renderSavedEvidenceGroups.contains("class=\"ann-row-num\""))
        assertTrue(renderSavedEvidenceGroups.contains("class=\"ann-row-title\""))
        assertTrue(renderSavedEvidenceGroups.contains("class=\"ann-row-comment"))
        assertTrue(renderSavedEvidenceGroups.contains("class=\"ann-row-status"))
        assertTrue(renderSavedEvidenceGroups.contains("targetLabel(item)"))
        assertTrue(renderSavedEvidenceGroups.contains("firstLine(item.comment || 'No comment')"))
        assertFalse(renderSavedEvidenceGroups.contains("evidence-card"))
        assertFalse(renderSavedEvidenceGroups.contains("screenshot attached"))
        assertFalse(renderSavedEvidenceGroups.contains("sourceHintLabel(item)"))
    }

    @Test
    fun consoleHtmlRendersOptionACanvasToolbar() {
        val html = FeedbackConsoleAssets.indexHtml
        val toolbarAnnotationCounts = javascriptFunctionBody(html, "toolbarAnnotationCounts")

        assertTrue(html.contains("frame.dataset.mode = mode"))
        assertFalse(html.contains("navigationControls.hidden"))
        assertFalse(html.contains("aria-label=\"Back\""))
        assertFalse(html.contains("aria-label=\"Swipe up\""))
        assertTrue(html.contains("class=\"tool-group\""))
        assertTrue(html.contains("id=\"toolStatus\""))
        assertTrue(html.contains("class=\"ts-meta\""))
        assertTrue(html.contains("class=\"ts-hint\""))
        assertTrue(html.contains("sessionSummaries: []"))
        assertTrue(html.contains("function selectedHistorySummary()"))
        assertTrue(html.contains("function toolbarAnnotationCounts()"))
        assertFalse(toolbarAnnotationCounts.contains("const summary = selectedHistorySummary();"))
        assertFalse(toolbarAnnotationCounts.contains("open: historyOpenCount(summary)"))
        assertFalse(toolbarAnnotationCounts.contains("resolved: historyDoneCount(summary)"))
        assertTrue(html.contains("state.sessionSummaries = sessionSummaries;"))
        assertTrue(html.contains("toolbarOpenCount()"))
        assertTrue(html.contains("toolbarResolvedCount()"))
        assertTrue(html.contains("Click a widget — or drag to draw a region"))
        assertTrue(html.contains("class=\"zoom-control\""))
        assertTrue(html.contains("id=\"zoomOutButton\""))
        assertTrue(html.contains("id=\"zoomPercent\""))
        assertTrue(html.contains("id=\"zoomInButton\""))
        assertTrue(html.contains("let previewZoom = 1"))
        assertTrue(html.contains("function applyPreviewZoom()"))
        assertTrue(html.contains("function setPreviewZoom(nextZoom)"))
        assertTrue(html.contains("frame.style.setProperty('--preview-zoom'"))
        assertTrue(html.contains("zoomOutButton.addEventListener('click'"))
        assertTrue(html.contains("zoomInButton.addEventListener('click'"))
        assertFalse(html.contains(".snapshot-frame::before"))
        assertTrue(html.contains("0 12px 24px -8px rgba(0, 0, 0, .4)"))
        assertTrue(html.contains("renderNumberedFeedbackOverlay"))
        assertTrue(html.contains("'#' + (index + 1)"))
    }

    @Test
    fun consoleHtmlCountsActivePendingAnnotationsInHistory() {
        val html = FeedbackConsoleAssets.indexHtml
        val historyOpenCount = javascriptFunctionBody(html, "historyOpenCount")
        val historyDoneCount = javascriptFunctionBody(html, "historyDoneCount")
        val createAnnotationFromSelection = javascriptFunctionBody(html, "createAnnotationFromSelection")
        val deletePendingFeedbackItem = javascriptFunctionBody(html, "deletePendingFeedbackItem")
        val renderAnnotationDetail = javascriptFunctionBody(html, "renderAnnotationDetail")

        assertTrue(html.contains("function pendingHistoryItemsForSession(session)"))
        assertTrue(historyOpenCount.contains("pendingHistoryItemsForSession(session)"))
        assertTrue(historyOpenCount.contains("(session.unresolvedItemsCount || 0) + (session.inProgressItemsCount || 0) + pending.filter(item => annotationStatus(item) !== 'resolved').length"))
        assertTrue(historyDoneCount.contains("pending.filter(item => annotationStatus(item) === 'resolved').length"))
        assertTrue(html.contains("function renderCurrentSessionList()"))
        assertTrue(createAnnotationFromSelection.contains("renderCurrentSessionList();"))
        assertTrue(deletePendingFeedbackItem.contains("renderCurrentSessionList();"))
        assertTrue(Regex("item\\.status = button\\.dataset\\.setStatus;[\\s\\S]*renderCurrentSessionList\\(\\);").containsMatchIn(renderAnnotationDetail))
    }

    @Test
    fun consoleHtmlLivePreviewImageUsesPreviewIdScopedScreenshotRoute() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("function previewScreenshotUrl(previewId)"))
        assertTrue(html.contains("return '/api/preview/' + encodeURIComponent(previewId) + '/screenshot/full';"))
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
    fun consoleHtmlInvalidatesPreviewContextOnDeviceAndSessionBoundaries() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("function invalidatePreviewContext()"))
        assertTrue(html.contains("previewRequestGeneration++;"))
        assertTrue(html.contains("previewRequestContextGeneration++;"))
        assertTrue(html.contains("state.preview = null;"))
        assertTrue(html.contains("previewRequestInFlight = null;"))
        assertTrue(html.contains("previewRequestInFlightContextGeneration = null;"))
        assertTrue(Regex("async function selectDevice\\(\\)[\\s\\S]*invalidatePreviewContext\\(\\);[\\s\\S]*/api/device/select").containsMatchIn(html))
        assertTrue(Regex("async function disconnectDevice\\(\\)[\\s\\S]*invalidatePreviewContext\\(\\);[\\s\\S]*/api/device/disconnect").containsMatchIn(html))
        assertTrue(Regex("async function openSession\\(sessionId\\)[\\s\\S]*invalidatePreviewContext\\(\\);[\\s\\S]*/api/session/open").containsMatchIn(html))
        assertTrue(Regex("async function openSession\\(sessionId\\)[\\s\\S]*await refresh\\(\\);[\\s\\S]*if \\(!latestPersistedScreen\\(\\) && shouldAutoFetchPreview\\(\\)\\) \\{[\\s\\S]*await refreshPreview\\(\\);[\\s\\S]*\\}[\\s\\S]*startLivePreviewPolling\\(\\);").containsMatchIn(html))
        assertTrue(Regex("function latestScreen\\(\\) \\{\\s+if \\(addItemsFlow\\) return addItemsFlow\\.screen;").containsMatchIn(html))
        assertTrue(Regex("if \\(focusedSavedItemId\\) \\{[\\s\\S]*?const focusedItem = savedEvidenceItems\\(\\)\\.find\\(item => item\\.itemId === focusedSavedItemId\\);").containsMatchIn(html))
        assertTrue(html.contains("return state.preview?.screen || latestPersistedScreen();"))
        assertTrue(Regex("async function newSession\\(\\)[\\s\\S]*invalidatePreviewContext\\(\\);[\\s\\S]*/api/session/open").containsMatchIn(html))
        assertTrue(Regex("async function closeSession\\(\\)[\\s\\S]*invalidatePreviewContext\\(\\);[\\s\\S]*/api/session/close").containsMatchIn(html))
        assertTrue(html.contains("const previousSelectedDeviceSerial = state.selectedDeviceSerial;"))
        assertTrue(html.contains("if (previousSelectedDeviceSerial !== selectedSerial) {"))
        assertFalse(html.contains("state.preview = null;\n              state.session = await requestJson('/api/session/open'"))
        assertFalse(html.contains("state.preview = null;\n              await refreshSessions();"))
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
        assertTrue(html.contains("screenshotUrl: previewScreenshotUrl(state.preview.previewId)"))
        assertTrue(Regex("finally \\{\\s+addItemsFlowStarting = false;\\s+updateComposerState\\(\\);\\s+if \\(!addItemsFlow\\) startLivePreviewPolling\\(\\);\\s+\\}").containsMatchIn(html))
        assertTrue(html.contains("if (addItemsFlowStarting) {"))
        assertTrue(html.contains("event.preventDefault();"))
        assertTrue(html.contains("annotateToolButton.addEventListener('click', () => enterAnnotateMode().catch(showError));"))
    }

    @Test
    fun consoleHtmlClearsSavedPreviewAndDoesNotAutoFetchWhenManual() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("state.preview = null;"))
        assertTrue(html.contains("function shouldAutoFetchPreview()"))
        assertTrue(html.contains("return configuredPreviewIntervalMs() != null && shouldPollPreview();"))
        assertTrue(html.contains("if (!document.hidden && shouldAutoFetchPreview()) refreshPreview().catch(showError);"))
        assertTrue(html.contains("if (shouldAutoFetchPreview()) return refreshPreview();"))
        assertFalse(html.contains("if (!document.hidden && shouldPollPreview()) refreshPreview().catch(showError);"))
        assertFalse(html.contains("if (shouldPollPreview()) return refreshPreview();"))
    }

    @Test
    fun consoleHtmlFocusesPendingItemWithoutDrawingUnnumberedSelectionOverlay() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("function focusedPendingSelectionSummary()"))
        assertTrue(html.contains("focusedPendingItemIndex != null"))
        assertTrue(html.contains("const item = focusedPendingSelectionSummary();"))
        assertTrue(html.contains("focusedPendingItemIndex = index;"))
        assertTrue(html.contains("currentSelection = null;"))
        assertFalse(html.contains("const item = pendingFeedbackItems[index];\n              currentSelection = item ?"))
        assertFalse(html.contains("label: item.targetType === 'node' ? 'Selected component' : 'Custom area'"))
    }

    @Test
    fun consoleHtmlImplementsSnapshotSelectionModes() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("let addItemsFlow"))
        assertTrue(html.contains("let pendingFeedbackItems"))
        assertTrue(html.contains("let currentSelection"))
        assertTrue(html.contains("finishAreaSelection"))
        assertTrue(html.contains("selectNodeAtPoint"))
        assertTrue(html.contains("nodesForHitTest"))
        assertTrue(html.contains("function nodesForHitTest(screen, nodesSelector)"))
        assertTrue(html.contains("function smallestContainingNode(nodes, point)"))
        assertTrue(html.contains("function hitTestNodes(screen)"))
        assertTrue(html.contains("let hoveredAnnotationTarget = null"))
        assertTrue(html.contains("function previewNodeAtPoint(event, image)"))
        assertTrue(html.contains("function confirmHoveredAnnotationTarget(event, image)"))
        assertTrue(html.contains("...(root?.mergedNodes || [])"))
        assertTrue(html.contains("...(root?.unmergedNodes || [])"))
        assertTrue(html.contains("const node = smallestContainingNode(hitTestNodes(screen), point);"))
        assertFalse(html.contains("const node = mergedNode || unmergedNode;"))
        assertTrue(html.contains("const seenNodeIds = new Set();"))
        assertTrue(html.contains("if (!node || !node.boundsInWindow) return;"))
        assertTrue(html.contains("unmergedNodes"))
        assertTrue(html.contains("if (!raw || raw.startsWith('compose:')) return '';"))
        assertTrue(html.contains("const label = firstMeaningful([...(node.text || []), node.editableText, ...(node.contentDescription || [])]);"))
        assertTrue(html.contains("if (role && label) return humanize(role) + ' \"' + label + '\"';"))
        assertTrue(html.contains("if (bounds) return 'Component ' + Math.round(bounds.right - bounds.left) + 'x' + Math.round(bounds.bottom - bounds.top);"))
        assertFalse(html.contains("const textValue = (node.text || [])[0] || (node.contentDescription || [])[0] || node.uid;"))
        assertTrue(html.contains("renderSelectionOverlay"))
        assertTrue(html.contains("dragPreview"))
        assertTrue(html.contains("hover-preview"))
        assertTrue(html.contains("pointermove"))
        assertTrue(html.contains("image.addEventListener('pointerleave', clearHoverPreview)"))
        assertTrue(html.contains("function clearDragState()"))
        assertTrue(html.contains("function clearHoverPreview()"))
        assertTrue(html.contains("if (!dragStart) {"))
        assertTrue(html.contains("previewNodeAtPoint(event, image);"))
        assertTrue(html.contains("confirmHoveredAnnotationTarget(event, image);"))
        assertTrue(html.contains("image.draggable = false"))
        assertTrue(html.contains("image.addEventListener('dragstart', event => event.preventDefault())"))
        assertTrue(html.contains("image.setPointerCapture?.(event.pointerId)"))
        assertTrue(html.contains("image.releasePointerCapture?.(event.pointerId)"))
        assertTrue(html.contains("image.addEventListener('pointercancel', clearDragState)"))
        assertTrue(html.contains("image.addEventListener('lostpointercapture', clearDragState)"))
        assertTrue(html.contains("function clamp(value, min, max)"))
        assertTrue(html.contains("naturalPointFromEvent"))
        assertTrue(html.contains("targetType"))
        assertTrue(html.contains("nodeUid"))
        assertTrue(html.contains("id=\"snapshotImage\""))
        assertTrue(html.contains("function updateComposerState"))
        assertTrue(html.contains("navigate('tap'"))
    }

    @Test
    fun consoleHtmlReportsNavigationCaptureErrors() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("navigation.captureError"))
        assertTrue(html.contains("Navigation performed, but capture failed:"))
    }

    @Test
    fun consoleHtmlAnnotationSaveUsesCurrentSelectionPayload() {
        val html = FeedbackConsoleAssets.indexHtml
        val createAnnotationFromSelection = javascriptFunctionBody(html, "createAnnotationFromSelection")

        assertTrue(html.contains("previewId: addItemsFlow.previewId"))
        assertTrue(html.contains("const payloadItems = pendingPayloadItems({ allowFallbackComments: allowFallbackComments, onlyWrittenComments: onlyWrittenComments, allowBlankComments: allowBlankComments })"))
        assertTrue(html.contains("items: payloadItems"))
        assertTrue(html.contains("targetType: selection.targetType"))
        assertTrue(html.contains("nodeUid: selection.nodeUid"))
        assertTrue(html.contains("bounds: selection.bounds"))
        assertTrue(html.contains("function pendingPayloadItems"))
        assertTrue(html.contains("function persistPendingFeedbackItems"))
        assertTrue(createAnnotationFromSelection.contains("toolMode = 'annotate';"))
        assertFalse(createAnnotationFromSelection.contains("toolMode = 'select';"))
        assertTrue(createAnnotationFromSelection.contains("focusedPendingItemIndex = pendingFeedbackItems.length - 1;"))
        assertTrue(html.contains("suppressNextClick = true;"))
        assertTrue(html.contains("function updateSelectedAnnotationComment"))
        assertTrue(html.contains("item.comment = comment.value;"))
        assertTrue(html.contains("Add a comment to every annotation before saving."))
        assertTrue(html.contains("Select a component or area first."))
    }

    @Test
    fun rejectsUnsupportedMethods() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/session")
            connection.requestMethod = "POST"
            assertEquals(405, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun previewRouteDoesNotAppendSessionScreens() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val before = fixThisJson.parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/session")).jsonObject

            val preview = fixThisJson.parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview")).jsonObject
            val after = fixThisJson.parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/session")).jsonObject

            assertTrue(preview.containsKey("screen"))
            assertTrue(before.getValue("screens").jsonArray.isEmpty())
            assertTrue(after.getValue("screens").jsonArray.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun savingDraftItemsAppendsOneScreenAndTwoItems() {
        val projectRoot = Files.createTempDirectory("fixthis-console-batch").toFile()
        try {
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1", "item-2").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                service.openSession(null, newSession = true)
                val preview = fixThisJson.parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview")).jsonObject
                val previewId = preview.getValue("previewId").jsonPrimitive.content

                val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(
                        """
                        {
                          "previewId": "$previewId",
                          "items": [
                            {
                              "targetType": "area",
                              "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                              "comment": "Change headline"
                            },
                            {
                              "targetType": "area",
                              "bounds": {"left":120.0,"top":200.0,"right":260.0,"bottom":280.0},
                              "comment": "Add margin"
                            }
                          ]
                        }
                        """.trimIndent().toByteArray(),
                    )
                }

                assertEquals(200, connection.responseCode)
                val session = fixThisJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
                assertEquals(1, session.getValue("screens").jsonArray.size)
                val items = session.getValue("items").jsonArray.map { it.jsonObject }
                assertEquals(2, items.size)
                assertEquals(listOf("Change headline", "Add margin"), items.map { it.getValue("comment").jsonPrimitive.content })
                assertEquals(
                    listOf("preview-screen-1", "preview-screen-1"),
                    items.map { it.getValue("screenId").jsonPrimitive.content },
                )
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun batchItemsApiReturnsConflictWhenLiveScreenFingerprintDiffersFromFrozenPreview() {
        val projectRoot = Files.createTempDirectory("fixthis-console-mismatch").toFile()
        try {
            val service = FeedbackSessionService(
                bridge = SequencedFingerprintBridge("frozen", "current"),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                service.openSession(null, newSession = true)
                val preview = fixThisJson.parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview")).jsonObject
                val previewId = preview.getValue("previewId").jsonPrimitive.content
                val frozenScreen = preview.getValue("screen").jsonObject

                val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(
                        """
                        {
                          "previewId": "$previewId",
                          "frozenFingerprint": "frozen",
                          "screen": $frozenScreen,
                          "items": [
                            {
                              "targetType": "area",
                              "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                              "comment": "Change headline"
                            }
                          ]
                        }
                        """.trimIndent().toByteArray(),
                    )
                }

                assertEquals(409, connection.responseCode)
                val payload = fixThisJson
                    .parseToJsonElement(connection.errorStream.bufferedReader().readText())
                    .jsonObject
                assertEquals("screen_fingerprint_mismatch", payload.getValue("error").jsonPrimitive.content)
                assertEquals("frozen", payload.getValue("frozenFingerprint").jsonPrimitive.content)
                assertEquals("current", payload.getValue("currentFingerprint").jsonPrimitive.content)
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun batchItemsApiReturnsFingerprintUnavailableHeaderWhenCurrentFingerprintIsMissing() {
        val projectRoot = Files.createTempDirectory("fixthis-console-null-fingerprint").toFile()
        try {
            val service = FeedbackSessionService(
                bridge = NullableSequencedFingerprintBridge("frozen", null),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                service.openSession(null, newSession = true)
                val preview = fixThisJson.parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview")).jsonObject
                val previewId = preview.getValue("previewId").jsonPrimitive.content
                val frozenScreen = preview.getValue("screen").jsonObject

                val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(
                        """
                        {
                          "previewId": "$previewId",
                          "frozenFingerprint": "frozen",
                          "screen": $frozenScreen,
                          "items": [
                            {
                              "targetType": "area",
                              "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                              "comment": "Change headline"
                            }
                          ]
                        }
                        """.trimIndent().toByteArray(),
                    )
                }

                assertEquals(200, connection.responseCode)
                assertEquals(
                    "current_fingerprint_unavailable",
                    connection.getHeaderField("X-FixThis-Fingerprint-Unavailable-Reason"),
                )
                val session = fixThisJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
                assertFalse(session.containsKey("fingerprintUnavailableReason"))
                assertEquals(1, session.getValue("screens").jsonArray.size)
                assertEquals(1, session.getValue("items").jsonArray.size)
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun batchItemsApiReturnsServerErrorWhenLiveRecaptureThrowsIllegalArgumentException() {
        val projectRoot = Files.createTempDirectory("fixthis-console-recapture-error").toFile()
        try {
            val service = FeedbackSessionService(
                bridge = SecondCaptureIllegalArgumentBridge(),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds(
                        "session-1",
                        "preview-1",
                        "preview-screen-1",
                        "recapture-screen-1",
                        "item-1",
                    ).next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                service.openSession(null, newSession = true)
                val preview = fixThisJson.parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview")).jsonObject
                val previewId = preview.getValue("previewId").jsonPrimitive.content

                val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(
                        """
                        {
                          "previewId": "$previewId",
                          "items": [
                            {
                              "targetType": "area",
                              "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                              "comment": "Change headline"
                            }
                          ]
                        }
                        """.trimIndent().toByteArray(),
                    )
                }

                assertEquals(500, connection.responseCode)
                assertTrue(connection.errorStream.bufferedReader().readText().contains("recapture failed"))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun savingDraftItemsAllowsBlankCommentsForUnwrittenAnnotations() {
        val projectRoot = Files.createTempDirectory("fixthis-console-blank-batch").toFile()
        try {
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                service.openSession(null, newSession = true)
                val preview = fixThisJson.parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview")).jsonObject
                val previewId = preview.getValue("previewId").jsonPrimitive.content

                val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(
                        """
                        {
                          "previewId": "$previewId",
                          "items": [
                            {
                              "targetType": "area",
                              "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                              "comment": ""
                            }
                          ]
                        }
                        """.trimIndent().toByteArray(),
                    )
                }

                assertEquals(200, connection.responseCode)
                val session = fixThisJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
                val item = session.getValue("items").jsonArray.single().jsonObject
                assertEquals("", item.getValue("comment").jsonPrimitive.content)
                assertEquals("open", item.getValue("status").jsonPrimitive.content)
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun batchItemsApiReturnsBadRequestForEmptyItemList() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"previewId":"preview-1","items":[]}""".toByteArray()) }

            assertEquals(400, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("At least one feedback item is required"))
            assertEquals(0, bridge.captureCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun batchItemsApiReturnsNotFoundForUnknownPreviewId() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {
                      "previewId": "missing-preview",
                      "items": [
                        {
                          "targetType": "area",
                          "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                          "comment": "Change headline"
                        }
                      ]
                    }
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(404, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("PREVIEW_NOT_FOUND"))
            assertEquals(0, bridge.captureCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun batchItemsApiReturnsBadRequestForInvalidPreviewTarget() {
        val bridge = FakeFixThisBridge()
        val projectRoot = Files.createTempDirectory("fixthis-console-invalid-target").toFile()
        try {
            val service = FeedbackSessionService(
                bridge = bridge,
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
                val preview = fixThisJson.parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview")).jsonObject
                val previewId = preview.getValue("previewId").jsonPrimitive.content

                val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(
                        """
                        {
                          "previewId": "$previewId",
                          "items": [
                            {
                              "targetType": "area",
                              "bounds": {"left":-1.0,"top":20.0,"right":110.0,"bottom":80.0},
                              "comment": "Change headline"
                            }
                          ]
                        }
                        """.trimIndent().toByteArray(),
                    )
                }

                assertEquals(400, connection.responseCode)
                assertTrue(connection.errorStream.bufferedReader().readText().contains("Selection bounds"))
                assertEquals(1, bridge.captureCount)
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun previewSaveInProgressMapsToConflict() {
        val method = Class
            .forName("io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerKt")
            .getDeclaredMethod("toConsoleHttpException", FeedbackSessionException::class.java)
        method.isAccessible = true

        val httpError = method.invoke(
            null,
            FeedbackSessionException("PREVIEW_SAVE_IN_PROGRESS: Preview is already being saved: preview-1"),
        )

        val statusCode = httpError.javaClass.getDeclaredField("statusCode")
        statusCode.isAccessible = true
        assertEquals(409, statusCode.get(httpError))
    }

    @Test
    fun previewScreenshotRouteServesLatestPreviewPng() = runBlocking {
        val projectRoot = Files.createTempDirectory("fixthis-console-preview").toFile()
        try {
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            val service = FeedbackSessionService(
                bridge = SessionScreenshotBridge(pngBytes),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next),
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
                val firstPreview = fixThisJson.parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview")).jsonObject
                val secondPreview = fixThisJson.parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview")).jsonObject
                val firstPreviewId = firstPreview.getValue("previewId").jsonPrimitive.content
                val secondPreviewId = secondPreview.getValue("previewId").jsonPrimitive.content

                val firstConnection = ConsoleHttpTestClient(server.url).connection("/api/preview/$firstPreviewId/screenshot/full")
                val secondConnection = ConsoleHttpTestClient(server.url).connection("/api/preview/$secondPreviewId/screenshot/full")

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
                val firstPreview = fixThisJson.parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview")).jsonObject
                repeat(3) { ConsoleHttpTestClient(server.url).get("/api/preview") }
                val firstPreviewId = firstPreview.getValue("previewId").jsonPrimitive.content

                val connection = ConsoleHttpTestClient(server.url).connection("/api/preview/$firstPreviewId/screenshot/full")

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
    fun devicesApiListsAndSelectsActiveDevice() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val devices = ConsoleHttpTestClient(server.url).get("/api/devices")
            assertTrue(devices.contains("SM_G986N"))
            assertTrue(devices.contains("adb-R3CN60LXW3L"))

            val select = ConsoleHttpTestClient(server.url).connection("/api/device/select")
            select.requestMethod = "POST"
            select.doOutput = true
            select.setRequestProperty("Content-Type", "application/json")
            select.outputStream.use { it.write("""{"serial":"adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp"}""".toByteArray()) }

            assertEquals(200, select.responseCode)
            assertEquals("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp", bridge.selectedDeviceSerial)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffApiSendsDraftAndClearsDraftList() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1", "batch-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.captureFakeScreenForTest(session.sessionId)
        service.addAreaFeedback(session.sessionId, screen.screenId, FixThisRect(0f, 0f, 10f, 10f), "Fix it")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":["item-1"]}""",
            )
            assertEquals(200, response.statusCode)
            val payload = fixThisJson.parseToJsonElement(response.body).jsonObject
            val sessionObj = payload["session"]!!.jsonObject
            assertTrue(sessionObj["handoffBatches"]?.jsonArray.orEmpty().isNotEmpty())
            assertEquals("sent", sessionObj["items"]?.jsonArray?.single()?.jsonObject?.get("delivery")?.jsonPrimitive?.content)
            val prompt = payload["prompt"]!!.jsonPrimitive.content
            assertTrue(prompt.contains("id: item-1"), "prompt should contain 'id: item-1', got:\n$prompt")
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffApiReturnsConflictWhenNoDraftItemsExist() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":["fake-id"]}""",
            )
            assertEquals(409, response.statusCode)
            assertTrue(response.body.contains("NO_DRAFT_FEEDBACK"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun clearDraftApiKeepsSentItems() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1", "batch-1", "item-2").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.captureFakeScreenForTest(session.sessionId)
        service.addAreaFeedback(session.sessionId, screen.screenId, FixThisRect(0f, 0f, 10f, 10f), "Sent")
        service.sendDraftToAgent(session.sessionId)
        service.addAreaFeedback(session.sessionId, screen.screenId, FixThisRect(10f, 10f, 20f, 20f), "Draft")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val clear = ConsoleHttpTestClient(server.url).connection("/api/items/draft")
            clear.requestMethod = "DELETE"

            assertEquals(200, clear.responseCode)
            val body = clear.inputStream.bufferedReader().readText()
            val comments = fixThisJson.parseToJsonElement(body).jsonObject
                .getValue("items")
                .jsonArray
                .map { it.jsonObject.getValue("comment").jsonPrimitive.content }
            assertEquals(listOf("Sent"), comments)
        } finally {
            server.stop()
        }
    }

    @Test
    fun deviceSelectApiReturnsBadRequestForBlankSerial() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val select = ConsoleHttpTestClient(server.url).connection("/api/device/select")
            select.requestMethod = "POST"
            select.doOutput = true
            select.setRequestProperty("Content-Type", "application/json")
            select.outputStream.use { it.write("""{"serial":" "}""".toByteArray()) }

            assertEquals(400, select.responseCode)
            assertTrue(select.errorStream.bufferedReader().readText().contains("Device serial"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun deviceSelectApiReturnsConflictForMissingSerialWithoutChangingSelection() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val select = ConsoleHttpTestClient(server.url).connection("/api/device/select")
            select.requestMethod = "POST"
            select.doOutput = true
            select.setRequestProperty("Content-Type", "application/json")
            select.outputStream.use { it.write("""{"serial":"missing-device"}""".toByteArray()) }

            assertEquals(409, select.responseCode)
            assertTrue(select.errorStream.bufferedReader().readText().contains("DEVICE_NOT_AVAILABLE"))
            assertEquals(null, bridge.selectedDeviceSerial)
        } finally {
            server.stop()
        }
    }

    @Test
    fun deviceSelectApiReturnsConflictForOfflineDeviceWithoutChangingSelection() {
        val bridge = DeviceListBridge(
            listOf(
                AdbDevice(
                    serial = "offline-device",
                    state = "offline",
                    model = "Pixel_8",
                    product = "shiba",
                    deviceName = "shiba",
                ),
            ),
        )
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val select = ConsoleHttpTestClient(server.url).connection("/api/device/select")
            select.requestMethod = "POST"
            select.doOutput = true
            select.setRequestProperty("Content-Type", "application/json")
            select.outputStream.use { it.write("""{"serial":"offline-device"}""".toByteArray()) }

            assertEquals(409, select.responseCode)
            assertTrue(select.errorStream.bufferedReader().readText().contains("DEVICE_NOT_AVAILABLE"))
            assertEquals(null, bridge.selectedDeviceSerial)
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemsApiUsesCapturedNodeBoundsInsteadOfRequestBounds() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "item-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val node = FixThisNode(
            uid = "compose:0:merged:10",
            composeNodeId = 10,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(10f, 20f, 110f, 70f),
            text = listOf("Pay now"),
        )
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 720f, 1600f), mergedNodes = listOf(node))),
                screenshot = SnapshotScreenshotDto(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/items")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {"screenId":"${screen.screenId}","targetType":"node","nodeUid":"${node.uid}","bounds":{"left":200.0,"top":300.0,"right":260.0,"bottom":340.0},"comment":"Button copy is unclear"}
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(200, connection.responseCode)
            val item = fixThisJson.decodeFromString(AnnotationDto.serializer(), connection.inputStream.bufferedReader().readText())
            assertEquals(AnnotationTargetDto.Node(node.uid, node.boundsInWindow), item.target)
            assertEquals(node, item.selectedNode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForUnknownScreenId() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/items")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {"screenId":"missing-screen","targetType":"area","bounds":{"left":0.0,"top":0.0,"right":10.0,"bottom":10.0},"comment":"Bad screen"}
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(400, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("SCREEN_NOT_FOUND"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForUnsupportedFields() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                screenshot = SnapshotScreenshotDto(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/items")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {"screenId":"${screen.screenId}","targetType":"area","bounds":{"left":0.0,"top":0.0,"right":10.0,"bottom":10.0},"comment":"Bad field","screenID":"typo"}
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(400, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Unsupported feedback item field"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForInvalidAreaBounds() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                screenshot = SnapshotScreenshotDto(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/items")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {"screenId":"${screen.screenId}","targetType":"area","bounds":{"left":-1.0,"top":0.0,"right":10.0,"bottom":10.0},"comment":"Bad bounds"}
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(400, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Selection bounds"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionsApiListsWorkspaces() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val sessions = ConsoleHttpTestClient(server.url).get("/api/sessions")

            assertTrue(sessions.contains("session-1"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionsApiFiltersByPackageNameQuery() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1", "session-2").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val matching = service.openSession("io.beyondwin.fixthis.sample", newSession = true)
        val other = service.openSession("io.beyondwin.fixthis.other", newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val sessions = ConsoleHttpTestClient(server.url).get("/api/sessions?packageName=io.beyondwin.fixthis.sample")

            assertTrue(sessions.contains(matching.sessionId))
            assertFalse(sessions.contains(other.sessionId))
        } finally {
            server.stop()
        }
    }

    @Test
    fun openSessionApiSwitchesCurrentSession() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1", "session-2").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val first = service.openSession(null, newSession = true)
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/session/open")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"sessionId":"${first.sessionId}"}""".toByteArray()) }

            assertEquals(200, connection.responseCode)
            assertTrue(connection.inputStream.bufferedReader().readText().contains(first.sessionId))
        } finally {
            server.stop()
        }
    }

    @Test
    fun openSessionApiReturnsNotFoundForUnknownSessionId() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/session/open")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"sessionId":"missing-session"}""".toByteArray()) }

            assertEquals(404, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Unknown feedback session"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionApiReturnsServerErrorForSessionSaveFailure() {
        val projectRoot = Files.createTempDirectory("fixthis-console-save-fail").toFile()
        try {
            projectRoot.resolve(".fixthis").writeText("blocked")
            val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(projectRoot), clock = { 100L })
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }, persistence = persistence),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = ConsoleHttpTestClient(server.url).connection(
                    "/api/session/open",
                    method = "POST",
                    body = """{"newSession":true}""",
                )

                assertEquals(500, connection.responseCode)
                assertTrue(connection.errorStream.bufferedReader().readText().contains("SESSION_SAVE_FAILED:"))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun closeSessionApiClosesCurrentSession() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/session/close")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("{}".toByteArray()) }

            assertEquals(200, connection.responseCode)
            val response = connection.inputStream.bufferedReader().readText()
            assertTrue(response.contains(session.sessionId))
            assertTrue(response.contains("closed"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun closeSessionApiReturnsNotFoundForUnknownSessionId() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/session/close")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"sessionId":"missing-session"}""".toByteArray()) }

            assertEquals(404, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Unknown feedback session"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun navigationApiPerformsAction() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(
            bridge,
            FeedbackSessionStore(),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val connection = ConsoleHttpTestClient(server.url).connection("/api/navigation")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"action":"back","captureAfter":false}""".toByteArray()) }

            assertEquals(200, connection.responseCode)
            val response = connection.inputStream.bufferedReader().readText()
            assertEquals(true, fixThisJson.parseToJsonElement(response).jsonObject["performed"]?.jsonPrimitive?.boolean)
            assertEquals(FeedbackNavigationAction.BACK, bridge.navigationRequests.single().action)
            assertFalse(bridge.navigationRequests.single().captureAfter)
        } finally {
            server.stop()
        }
    }

    @Test
    fun navigationApiRejectsUnknownAutomationFields() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(
            bridge,
            FeedbackSessionStore(),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val payloads = listOf(
                """{"action":"back","sequence":[]}""",
                """{"action":"back","script":"adb shell input keyevent BACK"}""",
            )

            payloads.forEach { payload ->
                val connection = ConsoleHttpTestClient(server.url).connection("/api/navigation")
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toByteArray()) }

                assertEquals(400, connection.responseCode)
                assertTrue(connection.errorStream.bufferedReader().readText().contains("Unsupported navigation field"))
            }
            assertTrue(bridge.navigationRequests.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun startUrlUsesConfiguredHostAndBoundPort() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, host = "127.0.0.1", port = 0)
        val url = server.start()
        try {
            assertTrue(url.startsWith("http://127.0.0.1:"))
            assertEquals(url, server.url)
            assertTrue(ConsoleHttpTestClient(url).get().contains("FixThis Feedback Console"))
        } finally {
            server.stop()
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

    @Test
    fun deleteScreenApiDeletesScreenAndLinkedItems() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null)
        service.addCapturedScreenForTest(session.sessionId, SnapshotDto("screen-1", 0L, displayName = "Main"))
        service.addAreaFeedback(session.sessionId, "screen-1", FixThisRect(0f, 0f, 10f, 10f), "Remove me")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/screens/screen-1")
            connection.requestMethod = "DELETE"

            assertEquals(200, connection.responseCode)
            val payload = fixThisJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            assertTrue(payload.getValue("screens").jsonArray.isEmpty())
            assertTrue(payload.getValue("items").jsonArray.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionsResponseIncludesEtag() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)
            val first = client.getResponse("/api/sessions")
            assertEquals(200, first.statusCode)
            val etag = first.header("ETag")
            assertNotNull(etag)
            assertTrue(etag.startsWith("\"") && etag.endsWith("\""), etag)
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionsReturns304ForMatchingIfNoneMatch() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)
            val first = client.getResponse("/api/sessions")
            val etag = first.header("ETag")!!
            val second = client.getResponse("/api/sessions", headers = mapOf("If-None-Match" to etag))
            assertEquals(304, second.statusCode)
            assertEquals(etag, second.header("ETag"))
            assertTrue(second.body.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionsEtagChangesAfterMutation() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)
            val first = client.getResponse("/api/sessions").header("ETag")!!
            service.openSession(packageNameOverride = "com.example.app", newSession = true)
            val second = client.getResponse("/api/sessions").header("ETag")!!
            assertNotEquals(first, second)
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionResponseIncludesEtag() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(packageNameOverride = "com.example.app", newSession = true)
            val response = ConsoleHttpTestClient(server.url).getResponse("/api/session")
            assertEquals(200, response.statusCode)
            assertNotNull(response.header("ETag"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionReturns304ForMatchingIfNoneMatch() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(packageNameOverride = "com.example.app", newSession = true)
            val client = ConsoleHttpTestClient(server.url)
            val first = client.getResponse("/api/session")
            val etag = first.header("ETag")!!
            val second = client.getResponse("/api/session", headers = mapOf("If-None-Match" to etag))
            assertEquals(304, second.statusCode)
            assertTrue(second.body.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionWithoutCurrentReturns200NullAndNoEtag() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).getResponse("/api/session")
            assertEquals(200, response.statusCode)
            assertEquals("null", response.body.trim())
            assertNull(response.header("ETag"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun historyPipsCollapseWorkingIntoOpen() {
        val html = FeedbackConsoleAssets.indexHtml
        assertFalse(html.contains("class=\"hi-pip working\""), "History pips must not render a separate working/WIP pip")
        val historyOpenCount = javascriptFunctionBody(html, "historyOpenCount")
        assertTrue(
            historyOpenCount.contains("(session.inProgressItemsCount || 0)"),
            "historyOpenCount must include in-progress items so WIP collapses into the open count",
        )
    }

    @Test
    fun historyPipDropsPointsLabel() {
        val html = FeedbackConsoleAssets.indexHtml
        val rendered = javascriptFunctionBody(html, "renderSessionsListFromPayload")
        assertFalse(rendered.contains("hi-pip points"), "Points pip must be removed")
    }

    @Test
    fun consoleHtmlContainsSessionsPolling() {
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(html.contains("function startSessionsPolling"), html.takeLast(2_000))
        assertTrue(html.contains("async function pollSessionsTick"), "Polling tick must exist")
    }

    @Test
    fun stalenessBannerElementExistsInHtml() {
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(html.contains("id=\"stalenessBanner\""), "banner element must exist")
        assertTrue(html.contains("data-headline"), "banner must have headline slot")
        assertTrue(html.contains("data-detail"), "banner must have detail slot")
        assertTrue(html.contains("data-dismiss"), "banner must have dismiss button")
    }

    @Test
    fun stalenessBannerStylesExistInHtml() {
        // FeedbackConsoleAssets inlines styles.css into indexHtml via the StylesPlaceholder,
        // so the CSS rules are observable as substrings in the rendered indexHtml.
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(html.contains(".staleness-banner"), "banner CSS class must exist")
    }

    @Test
    fun bootSequenceCallsCheckServerStaleness() {
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(
            html.contains("checkServerStaleness().catch"),
            "boot must invoke checkServerStaleness with a catch",
        )
    }

    @Test
    fun stalenessExposesMinimumProtocolVersion() {
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(
            html.contains("const MinimumSupportedProtocolVersion"),
            "must expose minimum supported protocol version constant",
        )
        assertTrue(
            html.contains("function checkProtocolCompat"),
            "must expose checkProtocolCompat function",
        )
    }

    @Test
    fun stalenessExposesProtocolVersionParser() {
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(
            html.contains("function parseProtocolVersion"),
            "must expose parseProtocolVersion helper",
        )
        assertTrue(
            html.contains("function compareProtocolVersion"),
            "must expose compareProtocolVersion helper",
        )
    }

    @Test
    fun protocolCompatBannersBothDirections() {
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "checkProtocolCompat")
        assertTrue(
            body.contains("protocol-sidekick-old-"),
            "must produce sidekick-old-direction hash",
        )
        assertTrue(
            body.contains("protocol-console-old-"),
            "must produce console-old-direction hash",
        )
        assertTrue(
            body.contains("compareProtocolVersion("),
            "must call compareProtocolVersion",
        )
        assertFalse(
            body.contains("=== MinimumSupportedProtocolVersion") ||
                body.contains("!== MinimumSupportedProtocolVersion"),
            "must NOT compare via string equality (use numeric compareProtocolVersion instead)",
        )
    }

    @Test
    fun applyConnectionStatusCallsCheckProtocolCompat() {
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "applyConnectionStatus")
        assertTrue(
            body.contains("checkProtocolCompat"),
            "applyConnectionStatus must invoke checkProtocolCompat",
        )
        assertTrue(
            body.contains("checkSidekickBuildEpoch"),
            "applyConnectionStatus must invoke checkSidekickBuildEpoch",
        )
    }

    private class FakeIds(vararg values: String) {
        private val queue = ArrayDeque(values.toList())
        val next: () -> String = { queue.removeFirst() }
    }

    private class FakeLongs(vararg values: Long) {
        private val queue = ArrayDeque(values.toList())
        val next: () -> Long = { queue.removeFirst() }
    }

    private fun FeedbackSessionService.captureFakeScreenForTest(sessionId: String): SnapshotDto = runBlocking { captureScreen(sessionId) }

    private fun FeedbackSessionService.addCapturedScreenForTest(sessionId: String, screen: SnapshotDto): SnapshotDto = javaClass.getDeclaredField("store").let { field ->
        field.isAccessible = true
        (field.get(this) as FeedbackSessionStore).addScreen(sessionId, screen)
    }

    private class DeviceListBridge(private val devices: List<AdbDevice>) : FixThisBridge {
        var selectedDeviceSerial: String? = null
            private set

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.beyondwin.fixthis.sample"

        override fun devices(): List<AdbDevice> = devices

        override fun selectedDeviceSerial(): String? = selectedDeviceSerial

        override fun selectDevice(serial: String) {
            selectedDeviceSerial = serial
        }

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(
            packageName: String,
            expectedText: String,
            role: String?,
        ): JsonObject = JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = JsonObject(emptyMap())
    }

    private class SessionScreenshotBridge(private val pngBytes: ByteArray) : FixThisBridge {
        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.beyondwin.fixthis.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(
            packageName: String,
            expectedText: String,
            role: String?,
        ): JsonObject = JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = buildJsonObject {
            val artifact = requireNotNull(destinationDirectory)
                .resolve("${requireNotNull(screenId)}-full.png")
            artifact.parentFile.mkdirs()
            artifact.writeBytes(pngBytes)
            put("activity", "MainActivity")
            put("sourceIndexAvailable", true)
            put(
                "inspection",
                buildJsonObject {
                    put("activity", "MainActivity")
                    put("roots", JsonArray(emptyList()))
                    put("errors", JsonArray(emptyList()))
                },
            )
            put(
                "screenshot",
                buildJsonObject {
                    put("desktopFullPath", artifact.absolutePath)
                },
            )
        }
    }

    private class SequencedSessionScreenshotBridge(vararg pngBytes: ByteArray) : FixThisBridge {
        private val queue = ArrayDeque(pngBytes.toList())

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.beyondwin.fixthis.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(
            packageName: String,
            expectedText: String,
            role: String?,
        ): JsonObject = JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = buildJsonObject {
            val artifact = requireNotNull(destinationDirectory)
                .resolve("${requireNotNull(screenId)}-full.png")
            artifact.parentFile.mkdirs()
            artifact.writeBytes(queue.removeFirst())
            put("activity", "MainActivity")
            put("sourceIndexAvailable", true)
            put(
                "inspection",
                buildJsonObject {
                    put("activity", "MainActivity")
                    put("roots", JsonArray(emptyList()))
                    put("errors", JsonArray(emptyList()))
                },
            )
            put(
                "screenshot",
                buildJsonObject {
                    put("desktopFullPath", artifact.absolutePath)
                },
            )
        }
    }

    private class SequencedFingerprintBridge(vararg fingerprints: String) : FixThisBridge {
        private val queue = ArrayDeque(fingerprints.toList())

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.beyondwin.fixthis.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(
            packageName: String,
            expectedText: String,
            role: String?,
        ): JsonObject = JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = buildJsonObject {
            val artifact = requireNotNull(destinationDirectory)
                .resolve("${requireNotNull(screenId)}-full.png")
            artifact.parentFile.mkdirs()
            artifact.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
            put("activity", "MainActivity")
            put("fingerprint", queue.removeFirst())
            put("sourceIndexAvailable", true)
            put(
                "inspection",
                buildJsonObject {
                    put("activity", "MainActivity")
                    put("roots", JsonArray(emptyList()))
                    put("errors", JsonArray(emptyList()))
                },
            )
            put(
                "screenshot",
                buildJsonObject {
                    put("desktopFullPath", artifact.absolutePath)
                },
            )
        }
    }

    private class NullableSequencedFingerprintBridge(vararg fingerprints: String?) : FixThisBridge {
        private val queue = ArrayDeque(fingerprints.toList())

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.beyondwin.fixthis.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(
            packageName: String,
            expectedText: String,
            role: String?,
        ): JsonObject = JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = buildJsonObject {
            val artifact = requireNotNull(destinationDirectory)
                .resolve("${requireNotNull(screenId)}-full.png")
            artifact.parentFile.mkdirs()
            artifact.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
            put("activity", "MainActivity")
            queue.removeFirst()?.let { put("fingerprint", it) }
            put("sourceIndexAvailable", true)
            put(
                "inspection",
                buildJsonObject {
                    put("activity", "MainActivity")
                    put("roots", JsonArray(emptyList()))
                    put("errors", JsonArray(emptyList()))
                },
            )
            put(
                "screenshot",
                buildJsonObject {
                    put("desktopFullPath", artifact.absolutePath)
                },
            )
        }
    }

    private class SecondCaptureIllegalArgumentBridge : FixThisBridge {
        private var captureCount = 0

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.beyondwin.fixthis.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(
            packageName: String,
            expectedText: String,
            role: String?,
        ): JsonObject = JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject {
            captureCount += 1
            require(captureCount != 2) { "recapture failed" }
            return buildJsonObject {
                val artifact = requireNotNull(destinationDirectory)
                    .resolve("${requireNotNull(screenId)}-full.png")
                artifact.parentFile.mkdirs()
                artifact.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
                put("activity", "MainActivity")
                put("fingerprint", "frozen")
                put("sourceIndexAvailable", true)
                put(
                    "inspection",
                    buildJsonObject {
                        put("activity", "MainActivity")
                        put("roots", JsonArray(emptyList()))
                        put("errors", JsonArray(emptyList()))
                    },
                )
                put(
                    "screenshot",
                    buildJsonObject {
                        put("desktopFullPath", artifact.absolutePath)
                    },
                )
            }
        }
    }

    private class BlockingCaptureBridge(
        private val previewStarted: CountDownLatch,
        private val releasePreview: CountDownLatch,
    ) : FixThisBridge {
        override fun resolvePackageName(packageOverride: String?): String = packageOverride ?: "io.beyondwin.fixthis.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject = JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = buildJsonObject {
            previewStarted.countDown()
            releasePreview.await(5, TimeUnit.SECONDS)
            put("activity", "MainActivity")
            put("sourceIndexAvailable", true)
            put(
                "inspection",
                buildJsonObject {
                    put("activity", "MainActivity")
                    put("roots", JsonArray(emptyList()))
                    put("errors", JsonArray(emptyList()))
                },
            )
            put(
                "screenshot",
                buildJsonObject {
                    put("width", 720)
                    put("height", 1600)
                },
            )
        }
    }

    private class LegacyScreenshotBridge(private val artifact: File) : FixThisBridge {
        override fun resolvePackageName(packageOverride: String?): String = packageOverride ?: "io.beyondwin.fixthis.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject = JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = buildJsonObject {
            put("activity", "MainActivity")
            put("sourceIndexAvailable", true)
            put(
                "inspection",
                buildJsonObject {
                    put("activity", "MainActivity")
                    put("roots", JsonArray(emptyList()))
                    put("errors", JsonArray(emptyList()))
                },
            )
            put(
                "screenshot",
                buildJsonObject {
                    put("desktopFullPath", artifact.absolutePath)
                },
            )
        }
    }

    private class ConsoleHttpTestClient(
        private val baseUrl: String,
        private val includeConsoleToken: Boolean = true,
    ) {
        private val consoleToken: String? by lazy {
            if (!includeConsoleToken) return@lazy null
            Regex("consoleToken:\\s*\"([^\"]+)\"")
                .find(java.net.URI(baseUrl).toURL().readText())
                ?.groupValues
                ?.get(1)
        }

        fun get(path: String = "/"): String = java.net.URI(baseUrl + path).toURL().readText()

        fun getResponse(path: String, headers: Map<String, String> = emptyMap()): ConsoleHttpResponse {
            val connection = java.net.URI(baseUrl + path).toURL().openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            if (path.startsWith("/api/")) {
                consoleToken?.let { connection.setRequestProperty(ConsoleTokenHeader, it) }
            }
            for ((name, value) in headers) {
                connection.setRequestProperty(name, value)
            }
            val statusCode = connection.responseCode
            val body = runCatching {
                (connection.errorStream ?: connection.inputStream)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                } ?: ""
            }.getOrDefault("")
            val headerFields: Map<String, List<String>> = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { (key, _) -> key!! }
            return ConsoleHttpResponse(statusCode = statusCode, body = body, headers = headerFields)
        }

        fun connection(path: String, method: String = "GET", body: String? = null): java.net.HttpURLConnection {
            val connection = java.net.URI(baseUrl + path).toURL().openConnection() as java.net.HttpURLConnection
            connection.requestMethod = method
            if (path.startsWith("/api/")) {
                consoleToken?.let { connection.setRequestProperty(ConsoleTokenHeader, it) }
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
            }
            return connection
        }

        fun postJson(path: String, body: String): ConsoleHttpResponse {
            val conn = java.net.URI(baseUrl + path).toURL().openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (path.startsWith("/api/")) {
                consoleToken?.let { conn.setRequestProperty(ConsoleTokenHeader, it) }
            }
            conn.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
            val statusCode = conn.responseCode
            val responseBody = runCatching {
                (conn.errorStream ?: conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            }.getOrDefault("")
            val headerFields: Map<String, List<String>> = conn.headerFields
                .filterKeys { it != null }
                .mapKeys { (key, _) -> key!! }
            return ConsoleHttpResponse(statusCode = statusCode, body = responseBody, headers = headerFields)
        }
    }

    private data class ConsoleHttpResponse(
        val statusCode: Int,
        val body: String,
        val headers: Map<String, List<String>>,
    ) {
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

        fun contentTypeStartsWith(prefix: String): Boolean = header("Content-Type")?.startsWith(prefix) == true
    }

    @Test
    fun consoleHtmlDeclaresPollingGlobals() {
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(html.contains("let pendingMutationCount"))
        assertTrue(html.contains("let sessionsPollingTimer"))
        assertTrue(html.contains("let lastSessionsEtag"))
        assertTrue(html.contains("let lastSessionEtag"))
        assertTrue(html.contains("async function withMutationLock"))
    }

    @Test
    fun saveToMcpToastMentionsAgentPickup() {
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(html.contains("Saved to MCP ✓ — agent will pick up"))
        assertFalse(html.contains("Saved to MCP ✓\","), "Old toast text must be gone")
    }

    @Test
    fun mutationsAreWrappedInLock() {
        val html = FeedbackConsoleAssets.indexHtml
        val sendAgent = javascriptFunctionBody(html, "sendAgentPrompt")
        val copyPrompt = javascriptFunctionBody(html, "copyPrompt")
        assertTrue(sendAgent.contains("withMutationLock"))
        assertTrue(copyPrompt.contains("withMutationLock"))
    }

    @Test
    fun mergeSessionIntoStatePreservesUserState() {
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "mergeSessionIntoState")
        assertTrue(body.contains("comment.value"), "Must preserve textarea value")
        assertTrue(body.contains("focusedSavedItemId") || body.contains("focusedPendingItemIndex"))
        assertTrue(body.contains("currentSelection"))
        assertTrue(body.contains("data-just-changed"))
    }

    @Test
    fun mergeSessionIntoStateSkipsHighlightOnBulkChange() {
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "mergeSessionIntoState")
        assertTrue(
            body.contains("BULK_CHANGE_HIGHLIGHT_THRESHOLD") || body.contains(">= 6") || body.contains("> 5"),
            "mergeSessionIntoState must guard against bulk highlight cascade",
        )
    }

    @Test
    fun startSessionsPollingIsCalledOnBoot() {
        val html = FeedbackConsoleAssets.indexHtml
        // Boot chain (16-space indent inside .then()): startSessionsPolling() must follow
        // startLivePreviewPolling() in the .then() block that already starts heartbeat + live-preview polling.
        assertTrue(
            html.contains(
                "                startHeartbeatPolling();\n" +
                    "                startLivePreviewPolling();\n" +
                    "                startSessionsPolling();\n" +
                    "              })",
            ),
            "main.js boot chain must call startSessionsPolling() after startLivePreviewPolling()",
        )
        // Visibility-change handler (14-space indent inside arrow body): must restart sessions polling
        // alongside the live-preview polling restart when the tab becomes visible again.
        assertTrue(
            html.contains(
                "              startLivePreviewPolling();\n" +
                    "              startSessionsPolling();\n" +
                    "            });",
            ),
            "visibilitychange handler must restart startSessionsPolling() when tab becomes visible",
        )
    }

    @Test
    fun sessionsPollingDeclaresFailureBackoffConstants() {
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(html.contains("let consecutivePollFailures"), "must declare failure counter")
        assertTrue(
            html.contains("MaxConsecutivePollFailures") || html.contains("= 5"),
            "must declare threshold (named const or literal 5)",
        )
    }

    @Test
    fun pollSessionsTickResetsFailureCounterOnSuccess() {
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "pollSessionsTick")
        assertTrue(
            body.contains("consecutivePollFailures = 0") || body.contains("consecutivePollFailures=0"),
            "tick must reset counter on success",
        )
    }

    @Test
    fun pollSessionsTickIncrementsFailureCounterOnError() {
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "pollSessionsTick")
        assertTrue(
            body.contains("consecutivePollFailures++") || body.contains("consecutivePollFailures += 1"),
            "tick must increment counter on failure",
        )
    }

    @Test
    fun pollSessionsTickPausesAfterThreshold() {
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "pollSessionsTick")
        assertTrue(
            body.contains("setSessionsPollingPaused(true)") || body.contains("stopSessionsPolling()"),
            "tick must pause polling once threshold reached",
        )
    }

    @Test
    fun stateConnectionDeclaresSessionsPollingPaused() {
        val html = FeedbackConsoleAssets.indexHtml
        // The flag must be declared on state.connection (or a sibling module-level let).
        assertTrue(
            html.contains("sessionsPollingPaused"),
            "must declare sessionsPollingPaused flag on state.connection",
        )
        assertTrue(
            html.contains("function setSessionsPollingPaused"),
            "must declare setSessionsPollingPaused helper",
        )
    }

    @Test
    fun renderConnectionSurfacesSessionsPollingPaused() {
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "renderConnection")
        assertTrue(
            body.contains("sessionsPollingPaused") || body.contains("Reconnecting feedback updates"),
            "renderConnection must consult the paused flag and surface a Reconnecting message",
        )
    }

    @Test
    fun visibilityChangeRecoversFromPolledFailure() {
        val html = FeedbackConsoleAssets.indexHtml
        // The visibilitychange handler must restart polling when paused.
        assertTrue(
            html.contains("sessionsPollingPaused") && html.contains("startSessionsPolling"),
            "visibility handler must consult sessionsPollingPaused and call startSessionsPolling",
        )
    }

    @Test
    fun withMutationLockRecoversFromPolledFailure() {
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "withMutationLock")
        assertTrue(
            body.contains("sessionsPollingPaused") || body.contains("startSessionsPolling"),
            "withMutationLock finally-block must restart polling if paused",
        )
    }

    @Test
    fun handoffPreviewEndpointReturnsMarkdownForRequestedItems() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/handoff-preview",
                body = """{"itemIds":["$itemId"]}""",
            )
            assertEquals(200, response.statusCode)
            assertTrue(response.contentTypeStartsWith("text/markdown"), "got: ${response.header("Content-Type")}")
            assertTrue(response.body.contains("id: $itemId"), "expected 'id: $itemId' in:\n${response.body}")
            assertTrue(response.body.contains("session_id: $sessionId"), "expected 'session_id:' in:\n${response.body}")
            assertTrue(response.body.contains("agent_protocol:"), "expected agent_protocol block in:\n${response.body}")
        } finally {
            server.stop()
        }
    }

    @Test
    fun handoffPreviewEndpointRejectsEmptyItemIds() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, _) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/handoff-preview",
                body = """{"itemIds":[]}""",
            )
            assertEquals(400, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun handoffPreviewEndpointReturns404ForUnknownSession() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/00000000-0000-0000-0000-000000000000/handoff-preview",
                body = """{"itemIds":["x"]}""",
            )
            assertEquals(404, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun handoffPreviewEndpointEmitsJsonErrorBody() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, _) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/handoff-preview",
                body = """{"itemIds":[]}""",
            )
            assertEquals(400, response.statusCode)
            assertTrue(response.contentTypeStartsWith("application/json"), "got: ${response.header("Content-Type")}")
            assertTrue(response.body.contains("\"error\""), "expected error JSON body, got:\n${response.body}")
            assertTrue(response.body.contains("itemIds must not be empty"), "expected reason in body, got:\n${response.body}")
        } finally {
            server.stop()
        }
    }

    private fun seedSessionWithOneItem(
        store: FeedbackSessionStore,
        service: FeedbackSessionService,
    ): Pair<String, String> {
        val session = service.openSession(null, newSession = true)
        store.addScreen(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Screen 1",
            ),
        )
        val item = store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = "screen-1",
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                comment = "Test feedback",
            ),
        )
        return Pair(session.sessionId, item.itemId)
    }

    @Test
    fun markHandedOffEndpointUpdatesLastHandedOffAtForItems() {
        var nowMillis = 100L
        val store = FeedbackSessionStore(clock = { nowMillis })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        // Promote DRAFT to SENT so the item carries SENT delivery before the call.
        service.sendDraftToAgent(sessionId, listOf(itemId))
        nowMillis = 500L
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/items/mark-handed-off",
                body = """{"itemIds":["$itemId"]}""",
            )
            assertEquals(200, response.statusCode)
            assertTrue(
                response.contentTypeStartsWith("application/json"),
                "got: ${response.header("Content-Type")}",
            )
            val item = store.getSession(sessionId).items.first { it.itemId == itemId }
            assertEquals(500L, item.lastHandedOffAtEpochMillis)
            assertEquals(500L, item.updatedAtEpochMillis)
        } finally {
            server.stop()
        }
    }

    @Test
    fun markHandedOffEndpointRejectsEmptyItemIds() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, _) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/items/mark-handed-off",
                body = """{"itemIds":[]}""",
            )
            assertEquals(400, response.statusCode)
            assertTrue(
                response.contentTypeStartsWith("application/json"),
                "got: ${response.header("Content-Type")}",
            )
            assertTrue(response.body.contains("\"error\""), "expected error JSON body, got:\n${response.body}")
        } finally {
            server.stop()
        }
    }

    @Test
    fun markHandedOffEndpointReturns404ForUnknownSession() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/00000000-0000-0000-0000-000000000000/items/mark-handed-off",
                body = """{"itemIds":["x"]}""",
            )
            assertEquals(404, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun markHandedOffEndpointRequiresConsoleToken() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url, includeConsoleToken = false).postJson(
                path = "/api/sessions/$sessionId/items/mark-handed-off",
                body = """{"itemIds":["$itemId"]}""",
            )
            assertEquals(403, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffsAcceptsItemIdsAndReturnsRenderedPrompt() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (_, itemId) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":["$itemId"]}""",
            )
            assertEquals(200, response.statusCode)
            val payload = fixThisJson.parseToJsonElement(response.body).jsonObject
            assertTrue(payload.containsKey("session"), "response should have 'session', got: ${response.body}")
            assertTrue(payload.containsKey("prompt"), "response should have 'prompt', got: ${response.body}")
            val prompt = payload["prompt"]!!.jsonPrimitive.content
            assertTrue(prompt.contains("id: $itemId"), "prompt should contain 'id: $itemId', got:\n$prompt")
            val sessionObj = payload["session"]!!.jsonObject
            val itemDelivery = sessionObj["items"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["itemId"]!!.jsonPrimitive.content == itemId }["delivery"]!!.jsonPrimitive.content
            assertEquals("sent", itemDelivery)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffsRejectsLegacyPromptBody() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"prompt":"# old format"}""",
            )
            assertEquals(400, response.statusCode)
            assertTrue(
                response.body.contains("itemIds"),
                "error message should mention itemIds, got: ${response.body}",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffsRejectsEmptyItemIds() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":[]}""",
            )
            assertEquals(400, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffsFlipsOnlySpecifiedItemIdsToSentLeavesOthersAsDraft() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, keepItemId) = seedSessionWithOneItem(store, service)
        // Add a second DRAFT item that should NOT be flipped
        val secondItem = store.addItem(
            sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = "screen-1",
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(5f, 6f, 7f, 8f)),
                comment = "second draft",
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":["$keepItemId"]}""",
            )
            assertEquals(200, response.statusCode)
            val sessionAfter = store.getSession(sessionId)
            val keptItem = sessionAfter.items.first { it.itemId == keepItemId }
            val otherItem = sessionAfter.items.first { it.itemId == secondItem.itemId }
            assertEquals(FeedbackDelivery.SENT, keptItem.delivery, "specified item should flip to SENT")
            assertEquals(FeedbackDelivery.DRAFT, otherItem.delivery, "unspecified item should remain DRAFT")
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionResponseIncludesStaleAfterHandoffFalseInitially() {
        val store = FeedbackSessionStore(clock = { 100L })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/session")
            val items = fixThisJson.parseToJsonElement(body).jsonObject
                .getValue("items").jsonArray
            assertEquals(1, items.size)
            val item = items[0].jsonObject
            assertTrue(item.containsKey("staleAfterHandoff"), "missing staleAfterHandoff: $item")
            assertEquals(false, item.getValue("staleAfterHandoff").jsonPrimitive.boolean)
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionResponseStaleAfterHandoffTrueWhenUpdatedAfterSend() {
        var nowMillis = 100L
        val store = FeedbackSessionStore(clock = { nowMillis })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        nowMillis = 200L
        service.sendDraftToAgent(sessionId, listOf(itemId))
        nowMillis = 500L
        service.updateDraftFeedback(sessionId, itemId, label = null, severity = null, comment = "edited", status = null)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/session")
            val item = fixThisJson.parseToJsonElement(body).jsonObject
                .getValue("items").jsonArray
                .single { it.jsonObject.getValue("itemId").jsonPrimitive.content == itemId }
                .jsonObject
            assertEquals(true, item.getValue("staleAfterHandoff").jsonPrimitive.boolean)
            assertEquals(200L, item.getValue("lastHandedOffAtEpochMillis").jsonPrimitive.long)
            assertEquals(500L, item.getValue("updatedAtEpochMillis").jsonPrimitive.long)
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionResponseStaleAfterHandoffFalseAfterReSave() {
        var nowMillis = 100L
        val store = FeedbackSessionStore(clock = { nowMillis })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        nowMillis = 200L
        service.sendDraftToAgent(sessionId, listOf(itemId))
        nowMillis = 500L
        service.updateDraftFeedback(sessionId, itemId, label = null, severity = null, comment = "edited", status = null)
        nowMillis = 700L
        service.sendDraftToAgent(sessionId, listOf(itemId))
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/session")
            val item = fixThisJson.parseToJsonElement(body).jsonObject
                .getValue("items").jsonArray
                .single { it.jsonObject.getValue("itemId").jsonPrimitive.content == itemId }
                .jsonObject
            assertEquals(false, item.getValue("staleAfterHandoff").jsonPrimitive.boolean)
            assertEquals(700L, item.getValue("lastHandedOffAtEpochMillis").jsonPrimitive.long)
        } finally {
            server.stop()
        }
    }

    private class FakeExchange(path: String) : HttpExchange() {
        private val uri = URI.create(path)

        override fun getRequestURI(): URI = uri
        override fun getRequestMethod(): String = "GET"
        override fun getRequestHeaders(): Headers = Headers()
        override fun getResponseHeaders(): Headers = Headers()
        override fun getRequestBody(): InputStream = InputStream.nullInputStream()
        override fun getResponseBody(): OutputStream = OutputStream.nullOutputStream()
        override fun sendResponseHeaders(responseCode: Int, responseLength: Long) = Unit
        override fun close() = Unit
        override fun getHttpContext(): HttpContext? = null
        override fun getRemoteAddress(): InetSocketAddress = InetSocketAddress(0)
        override fun getLocalAddress(): InetSocketAddress = InetSocketAddress(0)
        override fun getProtocol(): String = "HTTP/1.1"
        override fun getAttribute(name: String): Any? = null
        override fun setAttribute(name: String, value: Any?) = Unit
        override fun setStreams(inputStream: InputStream?, outputStream: OutputStream?) = Unit
        override fun getPrincipal(): HttpPrincipal? = null
        override fun getResponseCode(): Int = -1
    }
}
