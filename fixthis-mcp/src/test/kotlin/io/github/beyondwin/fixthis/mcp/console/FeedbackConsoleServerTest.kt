package io.github.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpPrincipal
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.github.beyondwin.fixthis.mcp.fixtures.rawHttpResponseCode
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
            assertTrue(session.contains("io.github.beyondwin.fixthis.sample"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun mutatingApiRequiresConsoleToken() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.github.beyondwin.fixthis.sample")
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
    fun authenticatedCapabilityAllowsMutationWithoutEmbeddingSecretInHtml() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.github.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val index = ConsoleHttpTestClient(server.url).get()
            val token = server.consoleTokenForTests()
            assertFalse(index.contains(token), "served HTML must not disclose the per-server capability")
            val connection = ConsoleHttpTestClient(server.url).connection("/api/items/draft")
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("X-FixThis-Console-Token", token)

            assertEquals(200, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun mutatingApiRejectsForbiddenOriginEvenWithConsoleToken() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.github.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val token = server.consoleTokenForTests()

            assertEquals(
                403,
                rawHttpResponseCode(
                    server.url,
                    method = "DELETE",
                    path = "/api/items/draft",
                    headers = mapOf(
                        CONSOLE_TOKEN_HEADER to token,
                        "Origin" to "https://example.invalid",
                    ),
                ),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun mutatingApiRejectsForbiddenHostEvenWithConsoleToken() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.github.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val token = server.consoleTokenForTests()

            assertEquals(
                403,
                rawHttpResponseCodeWithHost(
                    server.url,
                    host = "example.invalid",
                    method = "DELETE",
                    path = "/api/items/draft",
                    headers = mapOf(
                        CONSOLE_TOKEN_HEADER to token,
                        "Origin" to server.originUrl,
                    ),
                ),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun mutatingApiAllowsLocalHostOriginAndToken() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.github.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val token = server.consoleTokenForTests()

            assertEquals(
                200,
                rawHttpResponseCodeWithHost(
                    server.url,
                    host = URI.create(server.url).authority,
                    method = "DELETE",
                    path = "/api/items/draft",
                    headers = mapOf(
                        CONSOLE_TOKEN_HEADER to token,
                        "Origin" to server.originUrl,
                    ),
                ),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun sensitiveGetApiRequiresConsoleToken() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.github.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url, includeConsoleToken = false).connection("/api/session")

            assertEquals(403, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun capabilityQueryAllowsHeaderlessBrowserSubresourceGet() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.github.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val token = server.consoleTokenForTests()
            val connection = java.net.URI("${server.originUrl}/api/session?consoleToken=$token")
                .toURL()
                .openConnection() as java.net.HttpURLConnection

            assertEquals(200, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun indexDisablesReferrerPropagation() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.github.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).getResponse("/")

            assertEquals(200, response.statusCode)
            assertEquals("no-referrer", response.headers.entries.first { it.key.equals("Referrer-Policy", ignoreCase = true) }.value.single())
            assertTrue(response.body.contains("<meta name=\"referrer\" content=\"no-referrer\">"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun browserConsoleUsesCurrentFeedbackContractLabels() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val index = ConsoleHttpTestClient(server.url).get()

            assertButtonContainsContractLabel(index, "copyPromptButton", "Copy Prompt")
            assertButtonContainsContractLabel(index, "sendAgentButton", "Save to MCP")
            assertButtonContainsContractLabel(index, "selectToolButton", "Select")
            assertButtonContainsContractLabel(index, "annotateToolButton", "Annotate")
            assertButtonContainsContractLabel(index, "refreshDevicesButton", "Refresh devices")
            assertButtonContainsContractLabel(index, "forgetDeviceButton", "Forget device")
            assertTrue(index.contains("id=\"inspectorFooter\""))
            assertTrue(index.contains("id=\"editorBack\""))
            assertTrue(index.contains("id=\"toastContainer\""))
            assertFalse(index.contains("id=\"addItemButton\""))
            assertFalse(index.contains("id=\"cancelAddFlowButton\""))
            assertFalse(index.contains("id=\"clearSelectionButton\""))
            assertFalse(index.contains("id=\"clearDraftButton\""))
            assertTrue(index.contains("class=\"device-forget-button icon-button\""))
            assertTrue(index.contains("aria-label=\"Forget device\""))
            assertFalse(index.contains("id=\"disconnectDeviceButton\""))
            assertFalse(index.contains("device-clear-button"))
            assertFalse(index.contains(">Clear selection</button>"))
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

    private fun rawHttpResponseCodeWithHost(
        baseUrl: String,
        host: String,
        method: String,
        path: String,
        headers: Map<String, String>,
    ): Int {
        val uri = URI.create(baseUrl)
        java.net.Socket(uri.host, uri.port).use { socket ->
            val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            writer.write("$method $path HTTP/1.1\r\n")
            writer.write("Host: $host\r\n")
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
    fun rejectsUnsupportedMethods() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.github.beyondwin.fixthis.sample")
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
    fun headRequestToIndexIsAcceptedForHealthChecks() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.github.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/")
            connection.requestMethod = "HEAD"
            assertEquals(200, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun startUrlUsesConfiguredHostAndBoundPort() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.github.beyondwin.fixthis.sample")
        val server = FeedbackConsoleServer(service = service, host = "127.0.0.1", port = 0)
        val url = server.start()
        try {
            assertTrue(url.startsWith("http://127.0.0.1:"))
            assertEquals(url, server.url)
            assertTrue(url.contains("#consoleToken="), "open URL must carry the capability in a non-referrer fragment")
            assertTrue(ConsoleHttpTestClient(url).get().contains("FixThis Feedback Console"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun startsConsoleAssetsWatcherOnlyInDirMode() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.github.beyondwin.fixthis.sample")
        val assetsDir = java.nio.file.Files.createTempDirectory("server-test-assets").toFile()
        try {
            java.io.File(assetsDir, "console-build-meta.json")
                .writeText("""{"buildEpochMs":0,"gitSha":"start1"}""" + "\n")
            java.io.File(assetsDir, "index.html").writeText("<html></html>")
            java.io.File(assetsDir, "styles.css").writeText("")
            java.io.File(assetsDir, "app.js").writeText("")

            val bus = io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus()
            val seen = java.util.concurrent.LinkedBlockingQueue<String>()
            val sub = bus.subscribe { ev ->
                if (ev.name == "console-assets-changed") {
                    seen.put(ev.data["buildHash"]!!.jsonPrimitive.content)
                }
            }
            val server = FeedbackConsoleServer(
                service = service,
                consoleAssetsDir = assetsDir,
                eventBus = bus,
            )
            try {
                server.start()
                Thread.sleep(1100)
                java.io.File(assetsDir, "console-build-meta.json")
                    .writeText("""{"buildEpochMs":0,"gitSha":"updated2"}""" + "\n")
                val seenHash = seen.poll(3, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals("updated2", seenHash)
            } finally {
                sub.close()
                server.stop()
            }
        } finally {
            assetsDir.deleteRecursively()
        }
    }

    @Test
    fun doesNotStartWatcherWhenAssetsDirIsNull() {
        val service = FeedbackSessionService(FakeFixThisBridge(), FeedbackSessionStore(), "/repo", "io.github.beyondwin.fixthis.sample")
        val bus = io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus()
        val seen = java.util.concurrent.LinkedBlockingQueue<String>()
        val sub = bus.subscribe { ev ->
            if (ev.name == "console-assets-changed") seen.put(ev.name)
        }
        val server = FeedbackConsoleServer(service = service, eventBus = bus)
        try {
            server.start()
            Thread.sleep(800)
            assertNull(seen.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {
            sub.close()
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
