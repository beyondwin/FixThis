package io.github.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessFailureCatalog
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.github.beyondwin.fixthis.mcp.session.FeedbackNavigationRequest
import io.github.beyondwin.fixthis.mcp.session.FeedbackNavigationResult
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.SnapshotDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLDecoder

internal class PreviewRoutes(
    private val service: FeedbackSessionService,
    private val eventBus: ConsoleEventBus,
) : ConsoleRoute {
    private val screenshots = PreviewScreenshotResponder(service)

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
                eventBus.emit(
                    "capture-ready",
                    buildJsonObject {
                        put("screen", fixThisJson.encodeToJsonElement(SnapshotDto.serializer(), screen).jsonObject)
                    },
                )
                exchange.sendJson(200, screen)
            }
            "/api/preview" -> exchange.handlePreviewCapture()
            "/api/preview/screenshot/full" -> exchange.requireMethod("GET") {
                screenshots.sendLatest(exchange)
            }
            "/api/navigation" -> exchange.handleNavigation()
            else -> {
                if (exchange.requestURI.path.isPreviewFullScreenshotPath()) {
                    exchange.requireMethod("GET") {
                        screenshots.sendExact(exchange, exchange.requestURI.path.previewIdFromScreenshotPath())
                    }
                }
            }
        }
    }

    private fun HttpExchange.handlePreviewCapture() = requireMethod("GET") {
        val session = service.requireCurrentSession()
        val preview = runBlocking { service.capturePreview(session.sessionId) }
        val classified = classifyPreviewAvailability(preview)
        eventBus.emitPreviewReady(session.sessionId, classified)
        sendJson(200, classified)
    }

    private fun classifyPreviewAvailability(preview: FeedbackPreviewSnapshot): FeedbackPreviewSnapshot {
        if (preview.hasScreenshotBytes()) return preview
        return preview.copy(
            previewAvailable = false,
            readiness = FirstRunReadinessFailureCatalog.captureUnavailable(
                cause = "Capture returned semantics with no screenshot bytes.",
                details = buildMap {
                    preview.screen.screenshot?.captureFailedReason
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put("rawError", it) }
                },
            ),
        )
    }

    private fun FeedbackPreviewSnapshot.hasScreenshotBytes(): Boolean {
        val shot = screen.screenshot ?: return false
        return !shot.fullPath.isNullOrBlank() || !shot.desktopFullPath.isNullOrBlank()
    }

    private fun HttpExchange.handleNavigation() = requireMethod("POST") {
        val request = decodeNavigationBody()
        val session = service.requireCurrentSession()
        val result = try {
            runBlocking { service.navigate(session.sessionId, request) }
        } catch (error: IllegalArgumentException) {
            throw FeedbackConsoleHttpException(400, error.message ?: "Invalid navigation request")
        }
        eventBus.emit(
            "navigation-updated",
            buildJsonObject {
                put(
                    "navigation",
                    fixThisJson.encodeToJsonElement(
                        FeedbackNavigationResult.serializer(),
                        result,
                    ).jsonObject,
                )
            },
        )
        sendJson(200, result)
    }

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

internal fun String.isPreviewFullScreenshotPath(): Boolean = split('/').size == 6 && startsWith("/api/preview/") && endsWith("/screenshot/full")

internal fun String.previewIdFromScreenshotPath(): String = URLDecoder.decode(split('/')[3], Charsets.UTF_8.name())

private val allowedNavigationRequestKeys = setOf("action", "x", "y", "direction", "distance", "captureAfter")
