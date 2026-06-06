package io.github.beyondwin.fixthis.mcp.session.draft

import io.github.beyondwin.fixthis.mcp.console.FeedbackPreviewSnapshot
import io.github.beyondwin.fixthis.mcp.session.dto.FeedbackNavigationRequest
import io.github.beyondwin.fixthis.mcp.session.dto.FeedbackNavigationResult
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPaths
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewCaptureService
import io.github.beyondwin.fixthis.mcp.session.preview.toCapturedScreen
import io.github.beyondwin.fixthis.mcp.tools.FixThisBridge
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Coordinates evidence capture: screenshots, preview snapshots, and navigation
 * follow-ups that bind to annotations.
 *
 * Split out of `FeedbackSessionService` (CH-4): the façade and HTTP routes
 * call into this for capture/navigate; deeper logic still lives in
 * `PreviewCaptureService` and the bridge.
 */
class EvidenceCoordinator(
    private val bridge: FixThisBridge,
    private val store: FeedbackSessionStore,
    private val previewCaptureService: PreviewCaptureService,
) {

    suspend fun captureScreen(sessionId: String): SnapshotDto {
        val session = store.getSession(sessionId)
        val screenId = store.nextId()
        val artifactDirectory = FeedbackSessionPaths(File(session.projectRoot))
            .screenArtifactDirectory(session.sessionId, screenId)
        val payload = bridge.captureScreenSnapshot(
            packageName = session.packageName,
            sessionId = session.sessionId,
            screenId = screenId,
            destinationDirectory = artifactDirectory,
        )
        val screen = payload.toCapturedScreen(
            screenId = screenId,
            fallbackDisplayName = "Screen ${session.screens.size + 1}",
        )
        return store.addScreen(sessionId, screen)
    }

    suspend fun capturePreview(sessionId: String): FeedbackPreviewSnapshot = previewCaptureService.capturePreview(store.getSession(sessionId))

    fun previewScreenshotFile(sessionId: String, previewId: String): File = previewCaptureService.previewScreenshotFile(sessionId, previewId)

    suspend fun navigate(
        sessionId: String,
        request: FeedbackNavigationRequest,
    ): FeedbackNavigationResult {
        request.validate()
        val session = store.getSession(sessionId)
        val bridgeResult = bridge.performNavigation(session.packageName, request)
        val performed = bridgeResult["performed"]?.jsonPrimitive?.booleanOrNull ?: false
        val activity = bridgeResult["activityName"]?.jsonPrimitive?.contentOrNull
            ?: bridgeResult["activity"]?.jsonPrimitive?.contentOrNull
        val message = bridgeResult["message"]?.jsonPrimitive?.contentOrNull
        if (!request.captureAfter || !performed) {
            return FeedbackNavigationResult(
                performed = performed,
                action = request.action,
                activityName = activity,
                message = message,
            )
        }

        return try {
            val screen = captureScreen(sessionId)
            FeedbackNavigationResult(
                performed = performed,
                action = request.action,
                activityName = activity,
                message = message,
                screen = screen,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            FeedbackNavigationResult(
                performed = performed,
                action = request.action,
                activityName = activity,
                message = message,
                captureError = error.message ?: error::class.java.simpleName,
            )
        }
    }
}
