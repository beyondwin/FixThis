package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpPrincipal
import io.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.beyondwin.fixthis.mcp.fixtures.consoleTokenFrom
import io.beyondwin.fixthis.mcp.fixtures.rawHttpResponseCode
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
