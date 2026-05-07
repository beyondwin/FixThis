package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import io.beyondwin.fixthis.mcp.session.AnnotationDto
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationRequest
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationResult
import io.beyondwin.fixthis.mcp.session.FeedbackQueueFormatter
import io.beyondwin.fixthis.mcp.session.SessionDto
import io.beyondwin.fixthis.mcp.session.FeedbackSessionException
import io.beyondwin.fixthis.mcp.session.FeedbackSessionList
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPaths
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class FeedbackConsoleServer(
    private val service: FeedbackSessionService,
    private val host: String = "127.0.0.1",
    private val port: Int = 0,
    private val consoleAssetsDir: File? = null,
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
                    exchange.sendText(200, FeedbackConsoleAssets.html(consoleAssetsDir), "text/html; charset=utf-8")
                }
                "/favicon.ico" -> exchange.requireMethod("GET") {
                    exchange.sendNoContent()
                }
                "/api/session" -> exchange.requireMethod("GET") {
                    exchange.sendJson(200, service.currentSession())
                }
                "/api/sessions" -> exchange.requireMethod("GET") {
                    exchange.sendJson(
                        200,
                        service.listSessions(
                            packageNameOverride = exchange.queryParameter("packageName"),
                            includeClosed = exchange.queryBoolean("includeClosed"),
                        ),
                    )
                }
                "/api/session/open" -> exchange.requireMethod("POST") {
                    val request = exchange.decodeOpenSessionBody()
                    exchange.sendJson(200, service.openSession(request.packageName, request.sessionId, request.newSession))
                }
                "/api/session/close" -> exchange.requireMethod("POST") {
                    val request = exchange.decodeOpenSessionBody()
                    val sessionId = request.sessionId ?: service.currentSession().sessionId
                    exchange.sendJson(200, service.closeSession(sessionId))
                }
                "/api/devices" -> exchange.requireMethod("GET") {
                    val selectedSerial = service.selectedDeviceSerial()
                    exchange.sendJson(
                        200,
                        ConsoleDeviceList(
                            devices = service.devices().map { it.toConsoleDevice(selectedSerial) },
                            selectedSerial = selectedSerial,
                        ),
                    )
                }
                "/api/device/select" -> exchange.requireMethod("POST") {
                    val request = exchange.decodeSelectDeviceBody()
                    try {
                        service.selectDevice(request.serial)
                    } catch (error: IllegalArgumentException) {
                        throw FeedbackConsoleHttpException(400, error.message ?: "Invalid device selection request")
                    }
                    val selectedSerial = service.selectedDeviceSerial()
                    exchange.sendJson(
                        200,
                        ConsoleDeviceList(
                            devices = service.devices().map { it.toConsoleDevice(selectedSerial) },
                            selectedSerial = selectedSerial,
                        ),
                    )
                }
                "/api/device/disconnect" -> exchange.requireMethod("POST") {
                    service.disconnectDevice()
                    exchange.sendJson(
                        200,
                        ConsoleDeviceList(devices = service.devices().map { it.toConsoleDevice(null) }),
                    )
                }
                "/api/capture" -> exchange.requireMethod("POST") {
                    val session = service.currentSession()
                    val screen = runBlocking { service.captureScreen(session.sessionId) }
                    exchange.sendJson(200, screen)
                }
                "/api/preview" -> exchange.requireMethod("GET") {
                    val session = service.currentSession()
                    exchange.sendJson(200, runBlocking { service.capturePreview(session.sessionId) })
                }
                "/api/preview/screenshot/full" -> exchange.requireMethod("GET") {
                    exchange.sendPreviewScreenshot()
                }
                "/api/navigation" -> exchange.requireMethod("POST") {
                    val request = exchange.decodeNavigationBody()
                    val session = service.currentSession()
                    val result = try {
                        runBlocking { service.navigate(session.sessionId, request) }
                    } catch (error: IllegalArgumentException) {
                        throw FeedbackConsoleHttpException(400, error.message ?: "Invalid navigation request")
                    }
                    exchange.sendJson(200, result)
                }
                "/api/items" -> exchange.requireMethod("POST") {
                    val request = exchange.decodeAddFeedbackItemBody()
                    val session = service.currentSession()
                    val item = try {
                        runBlocking {
                            service.addFeedbackItem(
                                sessionId = session.sessionId,
                                screenId = request.screenId,
                                targetType = request.targetType,
                                bounds = request.bounds,
                                nodeUid = request.nodeUid,
                                comment = request.comment,
                            )
                        }
                    } catch (error: IllegalArgumentException) {
                        throw FeedbackConsoleHttpException(400, error.message ?: "Invalid feedback item request")
                    }
                    exchange.sendJson(200, item)
                }
                "/api/items/batch" -> exchange.requireMethod("POST") {
                    val request = exchange.decodeSavePreviewFeedbackItemsBody()
                    val session = try {
                        service.savePreviewFeedbackItems(
                            sessionId = service.currentSession().sessionId,
                            previewId = request.previewId,
                            items = request.items,
                        )
                    } catch (error: IllegalArgumentException) {
                        throw FeedbackConsoleHttpException(400, error.message ?: "Invalid feedback item request")
                    }
                    exchange.sendJson(200, session)
                }
                "/api/items/draft" -> exchange.requireMethod("DELETE") {
                    exchange.sendJson(200, service.clearDraftItems(service.currentSession().sessionId))
                }
                "/api/agent-handoffs" -> exchange.requireMethod("POST") {
                    val request = exchange.decodeAgentHandoffBody()
                    exchange.sendJson(200, service.sendDraftToAgent(service.currentSession().sessionId, request.prompt))
                }
                "/api/export/markdown" -> exchange.requireMethod("GET") {
                    exchange.sendText(200, FeedbackQueueFormatter.toMarkdown(service.currentSession()), "text/markdown; charset=utf-8")
                }
                else -> {
                    if (exchange.requestURI.path.isPreviewFullScreenshotPath()) {
                        exchange.requireMethod("GET") {
                            exchange.sendPreviewScreenshot(exchange.requestURI.path.previewIdFromScreenshotPath())
                        }
                    } else if (exchange.requestURI.path.isFullScreenshotPath()) {
                        exchange.requireMethod("GET") {
                            exchange.sendScreenshot(exchange.requestURI.path.screenIdFromScreenshotPath())
                        }
                    } else if (exchange.requestURI.path.isScreenPath()) {
                        exchange.requireMethod("DELETE") {
                            exchange.sendJson(
                                200,
                                service.deleteScreen(
                                    service.currentSession().sessionId,
                                    exchange.requestURI.path.screenIdFromScreenPath(),
                                ),
                            )
                        }
                    } else {
                        exchange.sendText(404, "Not found", "text/plain; charset=utf-8")
                    }
                }
            }
        } catch (error: FeedbackConsoleHttpException) {
            exchange.sendText(error.statusCode, error.message ?: "Request failed", "text/plain; charset=utf-8")
        } catch (error: FeedbackSessionException) {
            val httpError = error.toConsoleHttpException()
            exchange.sendText(httpError.statusCode, httpError.message, "text/plain; charset=utf-8")
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
        val legacyArtifactsDir = File(session.projectRoot, ".fixthis/artifacts").canonicalFile
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

    private fun HttpExchange.sendPreviewScreenshot(previewId: String) {
        val session = service.currentSession()
        val screenshotFile = service.previewScreenshotFile(session.sessionId, previewId)
        sendBytes(200, screenshotFile.readBytes(), "image/png")
    }

    private fun HttpExchange.sendPreviewScreenshot() {
        val session = service.currentSession()
        val projectRoot = File(session.projectRoot).canonicalFile
        val previewRoot = File(projectRoot, ".fixthis/preview-cache/${session.sessionId}").canonicalFile
        val persistedRoot = FeedbackSessionPaths(projectRoot).rootDirectory
        val legacyRoot = File(projectRoot, ".fixthis/artifacts").canonicalFile
        val roots = listOf(previewRoot, persistedRoot, legacyRoot)
        val screenshotFile = latestPreviewScreenshot(previewRoot, roots)
            ?: latestPersistedScreenshot(session, roots)
            ?: throw FeedbackConsoleHttpException(404, "Screenshot not found")

        sendBytes(200, screenshotFile.readBytes(), "image/png")
    }

    private fun latestPreviewScreenshot(previewRoot: File, allowedRoots: List<File>): File? {
        if (!previewRoot.isDirectory) return null
        return previewRoot
            .walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith("-full.png") }
            .map { file -> file.canonicalFile }
            .filter { file -> file.isAllowedPngArtifact(allowedRoots) }
            .maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.absolutePath })
    }

    private fun latestPersistedScreenshot(session: SessionDto, allowedRoots: List<File>): File? =
        session.screens
            .asReversed()
            .asSequence()
            .mapNotNull { screen -> screen.screenshot?.desktopFullPath?.let(::File) }
            .map { file -> file.canonicalFile }
            .firstOrNull { file -> file.isAllowedPngArtifact(allowedRoots) }

    private fun File.isAllowedPngArtifact(allowedRoots: List<File>): Boolean =
        isFile &&
            extension.lowercase() == "png" &&
            allowedRoots.any { root -> toPath().startsWith(root.toPath()) }

    private fun HttpExchange.requireMethod(method: String, block: () -> Unit) {
        if (requestMethod != method) {
            responseHeaders.add("Allow", method)
            sendText(405, "Method not allowed", "text/plain; charset=utf-8")
            return
        }
        block()
    }

    private fun HttpExchange.queryParameter(name: String): String? =
        requestURI.rawQuery
            ?.split("&")
            ?.firstNotNullOfOrNull { parameter ->
                val pieces = parameter.split("=", limit = 2)
                URLDecoder.decode(pieces[0], Charsets.UTF_8.name())
                    .takeIf { it == name }
                    ?.let {
                        URLDecoder.decode(pieces.getOrElse(1) { "" }, Charsets.UTF_8.name())
                    }
            }

    private fun HttpExchange.queryBoolean(name: String): Boolean {
        val value = queryParameter(name) ?: return false
        return value.toBooleanStrictOrNull()
            ?: throw FeedbackConsoleHttpException(400, "Invalid boolean query parameter: $name")
    }

    private fun HttpExchange.decodeAddFeedbackItemBody(): AddAnnotationRequest {
        val body = requestBody.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        return runCatching {
            val jsonObject = fixThisJson.parseToJsonElement(body) as? JsonObject
                ?: throw IllegalArgumentException("Feedback item request body must be a JSON object")
            val unsupportedKey = jsonObject.keys.firstOrNull { it !in allowedAddFeedbackItemRequestKeys }
            if (unsupportedKey != null) {
                throw IllegalArgumentException("Unsupported feedback item field: $unsupportedKey")
            }
            fixThisJson.decodeFromString(AddAnnotationRequest.serializer(), body)
        }.getOrElse { error ->
            throw FeedbackConsoleHttpException(400, error.message ?: "Invalid JSON request body")
        }
    }

    private fun HttpExchange.decodeSavePreviewFeedbackItemsBody(): SaveSnapshotRequest {
        val body = requestBody.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        return runCatching {
            fixThisJson.decodeFromString(SaveSnapshotRequest.serializer(), body)
        }.getOrElse { error ->
            throw FeedbackConsoleHttpException(400, error.message ?: "Invalid JSON request body")
        }
    }

    private fun HttpExchange.decodeAgentHandoffBody(): AgentHandoffRequest {
        val body = requestBody.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        if (body.isBlank()) return AgentHandoffRequest()
        return runCatching {
            fixThisJson.decodeFromString(AgentHandoffRequest.serializer(), body)
        }.getOrElse { error ->
            throw FeedbackConsoleHttpException(400, error.message ?: "Invalid JSON request body")
        }
    }

    private fun HttpExchange.decodeSelectDeviceBody(): SelectDeviceRequest {
        val body = requestBody.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        return runCatching {
            fixThisJson.decodeFromString(SelectDeviceRequest.serializer(), body)
        }.getOrElse { error ->
            throw FeedbackConsoleHttpException(400, error.message ?: "Invalid JSON request body")
        }
    }

    private fun HttpExchange.decodeOpenSessionBody(): OpenSessionRequest {
        val body = requestBody.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        return runCatching {
            fixThisJson.decodeFromString(OpenSessionRequest.serializer(), body)
        }.getOrElse { error ->
            throw FeedbackConsoleHttpException(400, error.message ?: "Invalid JSON request body")
        }
    }

    private fun HttpExchange.decodeNavigationBody(): FeedbackNavigationRequest {
        val body = requestBody.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        return runCatching {
            val jsonObject = fixThisJson.parseToJsonElement(body) as? JsonObject
                ?: throw IllegalArgumentException("Navigation request body must be a JSON object")
            val unsupportedKey = jsonObject.keys.firstOrNull { it !in allowedNavigationRequestKeys }
            if (unsupportedKey != null) {
                throw IllegalArgumentException("Unsupported navigation field: $unsupportedKey")
            }
            fixThisJson.decodeFromString(FeedbackNavigationRequest.serializer(), body)
        }.getOrElse { error ->
            throw FeedbackConsoleHttpException(400, error.message ?: "Invalid JSON request body")
        }
    }

    private fun HttpExchange.sendJson(statusCode: Int, value: SessionDto) {
        sendText(statusCode, fixThisJson.encodeToString(SessionDto.serializer(), value), "application/json; charset=utf-8")
    }

    private fun HttpExchange.sendJson(statusCode: Int, value: FeedbackSessionList) {
        sendText(statusCode, fixThisJson.encodeToString(FeedbackSessionList.serializer(), value), "application/json; charset=utf-8")
    }

    private fun HttpExchange.sendJson(statusCode: Int, value: SnapshotDto) {
        sendText(statusCode, fixThisJson.encodeToString(SnapshotDto.serializer(), value), "application/json; charset=utf-8")
    }

    private fun HttpExchange.sendJson(statusCode: Int, value: FeedbackPreviewSnapshot) {
        sendText(statusCode, fixThisJson.encodeToString(FeedbackPreviewSnapshot.serializer(), value), "application/json; charset=utf-8")
    }

    private fun HttpExchange.sendJson(statusCode: Int, value: AnnotationDto) {
        sendText(statusCode, fixThisJson.encodeToString(AnnotationDto.serializer(), value), "application/json; charset=utf-8")
    }

    private fun HttpExchange.sendJson(statusCode: Int, value: FeedbackNavigationResult) {
        sendText(statusCode, fixThisJson.encodeToString(FeedbackNavigationResult.serializer(), value), "application/json; charset=utf-8")
    }

    private fun HttpExchange.sendJson(statusCode: Int, value: ConsoleDeviceList) {
        sendText(statusCode, fixThisJson.encodeToString(ConsoleDeviceList.serializer(), value), "application/json; charset=utf-8")
    }

    private fun HttpExchange.sendText(statusCode: Int, text: String, contentType: String) {
        sendBytes(statusCode, text.toByteArray(Charsets.UTF_8), contentType)
    }

    private fun HttpExchange.sendBytes(statusCode: Int, bytes: ByteArray, contentType: String) {
        responseHeaders.set("Content-Type", contentType)
        sendResponseHeaders(statusCode, bytes.size.toLong())
        responseBody.use { output -> output.write(bytes) }
    }

    private fun HttpExchange.sendNoContent() {
        sendResponseHeaders(204, -1)
        close()
    }

    @Serializable
    private data class OpenSessionRequest(
        val packageName: String? = null,
        val sessionId: String? = null,
        val newSession: Boolean = false,
    )
}

private fun String.isFullScreenshotPath(): Boolean =
    split('/').size == 6 && startsWith("/api/screens/") && endsWith("/screenshot/full")

private fun String.screenIdFromScreenshotPath(): String =
    split('/')[3]

private fun String.isScreenPath(): Boolean =
    split('/').size == 4 && startsWith("/api/screens/")

private fun String.screenIdFromScreenPath(): String =
    URLDecoder.decode(split('/')[3], Charsets.UTF_8.name())

private fun String.isPreviewFullScreenshotPath(): Boolean =
    split('/').size == 6 && startsWith("/api/preview/") && endsWith("/screenshot/full")

private fun String.previewIdFromScreenshotPath(): String =
    URLDecoder.decode(split('/')[3], Charsets.UTF_8.name())

private fun String.toUrlHost(): String =
    if (contains(':') && !startsWith("[")) "[$this]" else this

private val allowedNavigationRequestKeys = setOf("action", "x", "y", "direction", "distance", "captureAfter")

private val allowedAddFeedbackItemRequestKeys = setOf("screenId", "comment", "targetType", "bounds", "nodeUid")

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

private class FeedbackConsoleHttpException(
    val statusCode: Int,
    override val message: String,
) : RuntimeException(message)
