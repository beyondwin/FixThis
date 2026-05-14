package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.beyondwin.fixthis.mcp.fixtures.ConsoleSourceFixtures
import io.beyondwin.fixthis.mcp.fixtures.consoleTokenFrom
import io.beyondwin.fixthis.mcp.fixtures.javascriptFunctionBody
import io.beyondwin.fixthis.mcp.fixtures.writeConsoleAssets
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
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
        val html = ConsoleSourceFixtures.readAll()
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
    fun consoleBundleEmbedsBuildEpochAndGitSha() {
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(
            html.contains("window.FixThisConsoleConfig.buildMeta"),
            "must embed buildMeta via FixThisConsoleConfig",
        )
        assertTrue(html.contains("buildEpochMs"), "must embed buildEpochMs in buildMeta")
        assertTrue(html.contains("gitSha"), "must embed gitSha in buildMeta")
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
