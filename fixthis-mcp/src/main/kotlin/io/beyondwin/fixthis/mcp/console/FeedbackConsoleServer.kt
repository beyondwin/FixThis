package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.beyondwin.fixthis.mcp.session.FeedbackSessionException
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class FeedbackConsoleServer(
    private val service: FeedbackSessionService,
    private val host: String = "127.0.0.1",
    private val port: Int = 0,
    private val consoleAssetsDir: File? = null,
) {
    private val lock = Any()
    private val routeTable = ConsoleRouteTable(
        listOf(
            SessionRoutes(service, consoleAssetsDir),
            DeviceRoutes(service),
            ConnectionRoutes(service),
            PreviewRoutes(service),
            FeedbackItemRoutes(service),
            ArtifactRoutes(service),
        ),
    )
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    val url: String
        get() = "http://${host.toUrlHost()}:${runningServer().address.port}"

    fun start(): String =
        synchronized(lock) {
            server?.let { return@synchronized url }
            val requestExecutor = consoleHttpExecutor()
            HttpServer.create(InetSocketAddress(InetAddress.getByName(host), port), 0)
                .also { httpServer ->
                    httpServer.createContext("/") { exchange -> handle(exchange) }
                    httpServer.executor = requestExecutor
                    httpServer.start()
                    executor = requestExecutor
                    server = httpServer
                }
            url
        }

    fun stop() {
        synchronized(lock) {
            server?.stop(0)
            server = null
            executor?.shutdownNow()
            executor = null
        }
    }

    private fun runningServer(): HttpServer =
        synchronized(lock) {
            server ?: throw IllegalStateException("Feedback console server is not running")
        }

    private fun handle(exchange: HttpExchange) {
        try {
            if (!routeTable.handle(exchange)) {
                exchange.sendText(404, "Not found", "text/plain; charset=utf-8")
            }
        } catch (error: FeedbackConsoleHttpException) {
            exchange.sendText(error.statusCode, error.message, "text/plain; charset=utf-8")
        } catch (error: FeedbackSessionException) {
            val httpError = error.toConsoleHttpException()
            exchange.sendText(httpError.statusCode, httpError.message, "text/plain; charset=utf-8")
        } catch (error: Throwable) {
            exchange.sendText(500, error.message ?: error::class.java.simpleName, "text/plain; charset=utf-8")
        }
    }
}

private fun String.toUrlHost(): String =
    if (contains(':') && !startsWith("[")) "[$this]" else this

private fun consoleHttpExecutor(): ExecutorService {
    val requestIds = AtomicInteger(0)
    return Executors.newCachedThreadPool { task ->
        Thread(task, "fixthis-console-http-${requestIds.incrementAndGet()}")
    }
}

private fun FeedbackSessionException.toConsoleHttpException(): FeedbackConsoleHttpException {
    val text = message ?: "Feedback session request failed"
    val statusCode = when {
        text.startsWith("SESSION_NOT_FOUND:") -> 404
        text.startsWith("Unknown feedback session:") -> 404
        text.startsWith("PREVIEW_NOT_FOUND:") -> 404
        text.startsWith("PREVIEW_SCREENSHOT_NOT_FOUND:") -> 404
        text.startsWith("SCREEN_NOT_FOUND:") -> 400
        text.startsWith("NO_DRAFT_FEEDBACK:") -> 409
        text.startsWith("DEVICE_NOT_AVAILABLE:") -> 409
        text.startsWith("PREVIEW_SAVE_IN_PROGRESS:") -> 409
        else -> 500
    }
    return FeedbackConsoleHttpException(statusCode, text)
}
