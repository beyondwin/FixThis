package io.github.pointpatch.mcp.console

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.pointpatch.cli.pointPatchJson
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.mcp.session.CapturedScreen
import io.github.pointpatch.mcp.session.FeedbackItem
import io.github.pointpatch.mcp.session.FeedbackQueueFormatter
import io.github.pointpatch.mcp.session.FeedbackSession
import io.github.pointpatch.mcp.session.FeedbackSessionPaths
import io.github.pointpatch.mcp.session.FeedbackSessionService
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

class FeedbackConsoleServer(
    private val service: FeedbackSessionService,
    private val host: String = "127.0.0.1",
    private val port: Int = 0,
) {
    private val lock = Any()
    private var server: HttpServer? = null

    val url: String
        get() = "http://${host.toUrlHost()}:${runningServer().address.port}"

    fun start(): String =
        synchronized(lock) {
            server?.let { return@synchronized url }
            HttpServer.create(InetSocketAddress(InetAddress.getByName(host), port), 0)
                .also { httpServer ->
                    httpServer.createContext("/") { exchange -> handle(exchange) }
                    httpServer.start()
                    server = httpServer
                }
            url
        }

    fun stop() {
        synchronized(lock) {
            server?.stop(0)
            server = null
        }
    }

    private fun runningServer(): HttpServer =
        synchronized(lock) {
            server ?: throw IllegalStateException("Feedback console server is not running")
        }

    private fun handle(exchange: HttpExchange) {
        try {
            when (exchange.requestURI.path) {
                "/" -> exchange.requireMethod("GET") {
                    exchange.sendText(200, FeedbackConsoleAssets.indexHtml, "text/html; charset=utf-8")
                }
                "/api/session" -> exchange.requireMethod("GET") {
                    exchange.sendJson(200, service.currentSession())
                }
                "/api/capture" -> exchange.requireMethod("POST") {
                    val session = service.currentSession()
                    val screen = runBlocking { service.captureScreen(session.sessionId) }
                    exchange.sendJson(200, screen)
                }
                "/api/items" -> exchange.requireMethod("POST") {
                    val request = exchange.decodeBody()
                    val session = service.currentSession()
                    val screen = session.screens.lastOrNull()
                        ?: throw FeedbackConsoleHttpException(409, "Capture a screen before adding feedback.")
                    val item = service.addAreaFeedback(
                        sessionId = session.sessionId,
                        screenId = screen.screenId,
                        bounds = request.bounds,
                        comment = request.comment,
                    )
                    exchange.sendJson(200, item)
                }
                "/api/export/markdown" -> exchange.requireMethod("GET") {
                    exchange.sendText(200, FeedbackQueueFormatter.toMarkdown(service.currentSession()), "text/markdown; charset=utf-8")
                }
                else -> {
                    if (exchange.requestURI.path.isFullScreenshotPath()) {
                        exchange.requireMethod("GET") {
                            exchange.sendScreenshot(exchange.requestURI.path.screenIdFromScreenshotPath())
                        }
                    } else {
                        exchange.sendText(404, "Not found", "text/plain; charset=utf-8")
                    }
                }
            }
        } catch (error: FeedbackConsoleHttpException) {
            exchange.sendText(error.statusCode, error.message ?: "Request failed", "text/plain; charset=utf-8")
        } catch (error: Throwable) {
            exchange.sendText(500, error.message ?: error::class.java.simpleName, "text/plain; charset=utf-8")
        }
    }

    private fun HttpExchange.sendScreenshot(screenId: String) {
        val session = service.currentSession()
        val screen = session.screens.firstOrNull { it.screenId == screenId }
            ?: throw FeedbackConsoleHttpException(404, "Screenshot not found")
        val screenshotPath = screen.screenshot?.desktopFullPath
            ?: throw FeedbackConsoleHttpException(404, "Screenshot not found")
        val screenshotFile = File(screenshotPath).canonicalFile
        val sessionArtifactsDir = FeedbackSessionPaths(File(session.projectRoot)).rootDirectory
        val legacyArtifactsDir = File(session.projectRoot, ".pointpatch/artifacts").canonicalFile
        val isAllowedArtifact = screenshotFile.toPath().startsWith(sessionArtifactsDir.toPath()) ||
            screenshotFile.toPath().startsWith(legacyArtifactsDir.toPath())
        if (
            !screenshotFile.isFile ||
            screenshotFile.extension.lowercase() != "png" ||
            !isAllowedArtifact
        ) {
            throw FeedbackConsoleHttpException(404, "Screenshot not found")
        }
        sendBytes(200, screenshotFile.readBytes(), "image/png")
    }

    private fun HttpExchange.requireMethod(method: String, block: () -> Unit) {
        if (requestMethod != method) {
            responseHeaders.add("Allow", method)
            sendText(405, "Method not allowed", "text/plain; charset=utf-8")
            return
        }
        block()
    }

    private fun HttpExchange.decodeBody(): AddItemRequest {
        val body = requestBody.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        return runCatching {
            pointPatchJson.decodeFromString(AddItemRequest.serializer(), body)
        }.getOrElse { error ->
            throw FeedbackConsoleHttpException(400, error.message ?: "Invalid JSON request body")
        }
    }

    private fun HttpExchange.sendJson(statusCode: Int, value: FeedbackSession) {
        sendText(statusCode, pointPatchJson.encodeToString(FeedbackSession.serializer(), value), "application/json; charset=utf-8")
    }

    private fun HttpExchange.sendJson(statusCode: Int, value: CapturedScreen) {
        sendText(statusCode, pointPatchJson.encodeToString(CapturedScreen.serializer(), value), "application/json; charset=utf-8")
    }

    private fun HttpExchange.sendJson(statusCode: Int, value: FeedbackItem) {
        sendText(statusCode, pointPatchJson.encodeToString(FeedbackItem.serializer(), value), "application/json; charset=utf-8")
    }

    private fun HttpExchange.sendText(statusCode: Int, text: String, contentType: String) {
        sendBytes(statusCode, text.toByteArray(Charsets.UTF_8), contentType)
    }

    private fun HttpExchange.sendBytes(statusCode: Int, bytes: ByteArray, contentType: String) {
        responseHeaders.set("Content-Type", contentType)
        sendResponseHeaders(statusCode, bytes.size.toLong())
        responseBody.use { output -> output.write(bytes) }
    }

    @Serializable
    private data class AddItemRequest(
        val comment: String = "",
        val bounds: PointPatchRect = PointPatchRect(left = 0f, top = 0f, right = 100f, bottom = 100f),
    )
}

private fun String.isFullScreenshotPath(): Boolean =
    split('/').size == 6 && startsWith("/api/screens/") && endsWith("/screenshot/full")

private fun String.screenIdFromScreenshotPath(): String =
    split('/')[3]

private fun String.toUrlHost(): String =
    if (contains(':') && !startsWith("[")) "[$this]" else this

private class FeedbackConsoleHttpException(
    val statusCode: Int,
    override val message: String,
) : RuntimeException(message)
