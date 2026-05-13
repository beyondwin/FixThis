package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationRequest
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPaths
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.SessionDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URLDecoder

internal class PreviewRoutes(private val service: FeedbackSessionService) : ConsoleRoute {
    override fun matches(path: String): Boolean = path == "/api/capture" ||
        path == "/api/preview" ||
        path == "/api/preview/screenshot/full" ||
        path == "/api/navigation" ||
        path.isPreviewFullScreenshotPath()

    override fun handle(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/api/capture" -> exchange.requireMethod("POST") {
                val session = service.requireCurrentSession()
                val screen = runBlocking { service.captureScreen(session.sessionId) }
                exchange.sendJson(200, screen)
            }
            "/api/preview" -> exchange.requireMethod("GET") {
                val session = service.requireCurrentSession()
                exchange.sendJson(200, runBlocking { service.capturePreview(session.sessionId) })
            }
            "/api/preview/screenshot/full" -> exchange.requireMethod("GET") {
                exchange.sendPreviewScreenshot()
            }
            "/api/navigation" -> exchange.requireMethod("POST") {
                val request = exchange.decodeNavigationBody()
                val session = service.requireCurrentSession()
                val result = try {
                    runBlocking { service.navigate(session.sessionId, request) }
                } catch (error: IllegalArgumentException) {
                    throw FeedbackConsoleHttpException(400, error.message ?: "Invalid navigation request")
                }
                exchange.sendJson(200, result)
            }
            else -> {
                if (exchange.requestURI.path.isPreviewFullScreenshotPath()) {
                    exchange.requireMethod("GET") {
                        exchange.sendPreviewScreenshot(exchange.requestURI.path.previewIdFromScreenshotPath())
                    }
                }
            }
        }
    }

    private fun HttpExchange.sendPreviewScreenshot(previewId: String) {
        val explicitSessionId = queryParameter("sessionId")?.takeIf { it.isNotBlank() }
        val session = explicitSessionId?.let { service.getSession(it) } ?: service.requireCurrentSession()
        val screenshotFile = try {
            service.previewScreenshotFile(session.sessionId, previewId)
        } catch (error: RuntimeException) {
            throw FeedbackConsoleHttpException(404, "Screenshot not found", error)
        }
        sendBytes(200, screenshotFile.readBytes(), "image/png")
    }

    private fun HttpExchange.sendPreviewScreenshot() {
        val session = queryParameter("sessionId")?.takeIf { it.isNotBlank() }?.let { service.getSession(it) }
            ?: service.requireCurrentSession()
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

    private fun latestPersistedScreenshot(session: SessionDto, allowedRoots: List<File>): File? = session.screens
        .asReversed()
        .asSequence()
        .mapNotNull { screen -> screen.screenshot?.desktopFullPath?.let(::File) }
        .map { file -> file.canonicalFile }
        .firstOrNull { file -> file.isAllowedPngArtifact(allowedRoots) }

    private fun HttpExchange.decodeNavigationBody(): FeedbackNavigationRequest {
        val body = requestBodyText()
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
}

internal fun File.isAllowedPngArtifact(allowedRoots: List<File>): Boolean = isFile &&
    extension.lowercase() == "png" &&
    allowedRoots.any { root -> toPath().startsWith(root.toPath()) }

internal fun String.isPreviewFullScreenshotPath(): Boolean = split('/').size == 6 && startsWith("/api/preview/") && endsWith("/screenshot/full")

internal fun String.previewIdFromScreenshotPath(): String = URLDecoder.decode(split('/')[3], Charsets.UTF_8.name())

private val allowedNavigationRequestKeys = setOf("action", "x", "y", "direction", "distance", "captureAfter")
