package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpPrincipal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private class ThrowingRoute(private val error: Throwable) : ConsoleRoute {
        override fun matches(path: String): Boolean = true
        override fun handle(exchange: HttpExchange) {
            throw error
        }
    }

    private class FakeHttpExchange(
        private val method: String,
        path: String,
    ) : HttpExchange() {
        private val uri = URI.create(path)
        private val requestHeaders = Headers()
        private val responseHeaders = Headers()
        private val responseBodyStream = ByteArrayOutputStream()
        var statusCode: Int = -1
            private set

        override fun getRequestURI(): URI = uri
        override fun getRequestMethod(): String = method
        override fun getRequestHeaders(): Headers = requestHeaders
        override fun getResponseHeaders(): Headers = responseHeaders
        override fun getRequestBody(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getResponseBody(): OutputStream = responseBodyStream
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
    }
}
