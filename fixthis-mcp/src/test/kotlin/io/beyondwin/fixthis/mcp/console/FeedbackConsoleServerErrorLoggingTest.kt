package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpPrincipal
import io.beyondwin.fixthis.cli.BridgeConnectionException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackConsoleServerErrorLoggingTest {
    @Test
    fun handlerExceptionsAreLoggedToDiagnosticsSink() {
        val sink = StringBuilder()
        val server = FeedbackConsoleServer(
            routes = listOf(ThrowingRoute(IllegalStateException("boom"))),
            diagnosticsSink = { sink.appendLine(it) },
        )
        val exchange = FakeHttpExchange(method = "GET", path = "/api/anything")

        server.dispatch(exchange)

        assertEquals(500, exchange.statusCode)
        val log = sink.toString()
        assertTrue(
            log.contains("IllegalStateException"),
            "Expected diagnostics sink to contain 'IllegalStateException', got: $log",
        )
        assertTrue(
            log.contains("boom"),
            "Expected diagnostics sink to contain 'boom', got: $log",
        )
    }

    @Test
    fun bridgeConnectionExceptionsReturnUnavailableWithoutDiagnosticsLog() {
        val sink = StringBuilder()
        val server = FeedbackConsoleServer(
            routes = listOf(ThrowingRoute(BridgeConnectionException("Bridge closed before sending a response"))),
            diagnosticsSink = { sink.appendLine(it) },
        )
        val exchange = FakeHttpExchange(method = "GET", path = "/api/preview")

        server.dispatch(exchange)

        assertEquals(503, exchange.statusCode)
        assertTrue(exchange.responseText().contains("Bridge closed before sending a response"))
        assertFalse(
            sink.toString().contains("BridgeConnectionException"),
            "Expected bridge connection failures to avoid diagnostics stack logs, got: $sink",
        )
    }

    @Test
    fun clientDisconnectsDuringResponseWriteAreNotLoggedAsServerErrors() {
        val sink = StringBuilder()
        val server = FeedbackConsoleServer(
            routes = listOf(BytesRoute()),
            diagnosticsSink = { sink.appendLine(it) },
        )
        val exchange = FakeHttpExchange(
            method = "GET",
            path = "/api/preview/preview-1/screenshot/full",
            responseBody = DisconnectingOutputStream(),
        )

        server.dispatch(exchange)

        assertEquals(200, exchange.statusCode)
        assertFalse(
            sink.toString().contains("Connection reset by peer"),
            "Expected cancelled browser image requests to avoid diagnostics stack logs, got: $sink",
        )
    }

    @Test
    fun clientDisconnectsDuringErrorResponseWriteAreNotRethrownOrLogged() {
        val sink = StringBuilder()
        val server = FeedbackConsoleServer(
            routes = listOf(ThrowingRoute(FeedbackConsoleHttpException(400, "bad request"))),
            diagnosticsSink = { sink.appendLine(it) },
        )
        val exchange = FakeHttpExchange(
            method = "GET",
            path = "/api/session",
            responseBody = DisconnectingOutputStream(),
        )

        server.dispatch(exchange)

        assertEquals(400, exchange.statusCode)
        assertFalse(
            sink.toString().contains("Connection reset by peer"),
            "Client disconnects while sending error JSON must not produce diagnostics logs: $sink",
        )
    }

    @Test
    fun serverVersionRouteUsesSharedSafeResponseWriter() {
        val source = java.nio.file.Files.readString(
            java.nio.file.Paths.get(
                "src/main/kotlin/io/beyondwin/fixthis/mcp/console/ServerVersionRoutes.kt",
            ),
        )

        assertTrue(source.contains("exchange.sendText(200, payload, \"application/json; charset=utf-8\")"))
        assertFalse(source.contains("exchange.responseBody.use"))
    }

    private class ThrowingRoute(private val error: Throwable) : ConsoleRoute {
        override fun matches(path: String): Boolean = true
        override fun handle(exchange: HttpExchange): Unit = throw error
    }

    private class BytesRoute : ConsoleRoute {
        override fun matches(path: String): Boolean = true
        override fun handle(exchange: HttpExchange) {
            exchange.sendBytes(200, byteArrayOf(1, 2, 3), "image/png")
        }
    }

    private class DisconnectingOutputStream : OutputStream() {
        override fun write(b: Int): Unit = throw IOException("Connection reset by peer")

        override fun write(b: ByteArray, off: Int, len: Int): Unit = throw IOException("Connection reset by peer")
    }

    private class FakeHttpExchange(
        private val method: String,
        path: String,
        private val responseBody: OutputStream = ByteArrayOutputStream(),
    ) : HttpExchange() {
        private val uri = URI.create(path)
        private val requestHeaders = Headers()
        private val responseHeaders = Headers()
        var statusCode: Int = -1
            private set

        override fun getRequestURI(): URI = uri
        override fun getRequestMethod(): String = method
        override fun getRequestHeaders(): Headers = requestHeaders
        override fun getResponseHeaders(): Headers = responseHeaders
        override fun getRequestBody(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getResponseBody(): OutputStream = responseBody
        override fun sendResponseHeaders(responseCode: Int, responseLength: Long) {
            statusCode = responseCode
        }
        override fun close() = Unit
        override fun getHttpContext(): HttpContext? = null
        override fun getRemoteAddress(): InetSocketAddress = InetSocketAddress(0)
        override fun getLocalAddress(): InetSocketAddress = InetSocketAddress(0)
        override fun getProtocol(): String = "HTTP/1.1"
        override fun getAttribute(name: String): Any? = null
        override fun setAttribute(name: String, value: Any?) = Unit
        override fun setStreams(inputStream: InputStream?, outputStream: OutputStream?) = Unit
        override fun getPrincipal(): HttpPrincipal? = null
        override fun getResponseCode(): Int = statusCode
        fun responseText(): String = (responseBody as? ByteArrayOutputStream)?.toString(Charsets.UTF_8.name()).orEmpty()
    }
}
