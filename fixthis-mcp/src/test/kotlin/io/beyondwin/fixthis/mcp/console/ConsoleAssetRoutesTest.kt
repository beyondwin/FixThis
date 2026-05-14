package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.beyondwin.fixthis.mcp.fixtures.consoleTokenFrom
import io.beyondwin.fixthis.mcp.fixtures.javascriptFunctionBody
import io.beyondwin.fixthis.mcp.fixtures.writeConsoleAssets
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleAssetRoutesTest {
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
            "connectionFsm.js",
            "connectionUseCases.js",
            "connectionBrowserAdapter.js",
            "previewFsm.js",
            "previewUseCases.js",
            "previewBrowserAdapter.js",
            "pollingFsm.js",
            "pollingUseCases.js",
            "pollingBrowserAdapter.js",
            "state.js",
            "staleness.js",
            "pendingPersistence.js",
            "draftWorkspace.js",
            "draftWorkspaceHistory.js",
            "draftPorts.js",
            "draftStorageAdapter.js",
            "beforeunloadGuard.js",
            "undoRedo.js",
            "undoKeymatch.js",
            "previewStaleness.js",
            "activityDrift.js",
            "api.js",
            "draftApiAdapter.js",
            "draftUseCases.js",
            "draftCommandQueue.js",
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
        // The header entry has the form:
        // // build-header\nconst ConsoleBuildEpochMs = N;\nconst ConsoleBuildGitSha = 'X';\n
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
}
