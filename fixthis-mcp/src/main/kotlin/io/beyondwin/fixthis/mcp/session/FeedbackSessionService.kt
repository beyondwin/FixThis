package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.cli.AdbDevice
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.console.AnnotationDraftDto
import io.beyondwin.fixthis.mcp.console.ConsoleConnectionStatus
import io.beyondwin.fixthis.mcp.console.FeedbackPreviewSnapshot
import io.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.beyondwin.fixthis.mcp.tools.FixThisBridge
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class FeedbackSessionService(
    private val bridge: FixThisBridge,
    private val store: FeedbackSessionStore = FeedbackSessionStore(),
    private val projectRoot: String,
    private val defaultPackageName: String? = null,
    private val previewCache: PreviewSnapshotCache = PreviewSnapshotCache(MaxRetainedPreviews),
    private val sourceIndexRegistry: SourceIndexRegistry = SourceIndexRegistry(),
) {
    private val connectionService = ConsoleConnectionService(bridge)
    private val screenshotArtifactPromoter = ScreenshotArtifactPromoter()
    private val targetEvidenceService = TargetEvidenceService(
        bridge,
        sourceIndexRegistry,
        projectRoot = File(projectRoot),
    )
    private val previewCaptureService = PreviewCaptureService(
        bridge = bridge,
        store = store,
        previewCache = previewCache,
        targetEvidenceService = targetEvidenceService,
    )
    private val feedbackDraftService = FeedbackDraftService(
        store = store,
        previewCache = previewCache,
        targetEvidenceService = targetEvidenceService,
        screenshotArtifactPromoter = screenshotArtifactPromoter,
    )
    private val sessionLock = Any()

    fun openSession(
        packageNameOverride: String?,
        sessionId: String? = null,
        newSession: Boolean = false,
    ): SessionDto =
        synchronized(sessionLock) {
            sessionId?.takeIf { it.isNotBlank() }?.let { return@synchronized store.openExistingSession(it) }
            val packageName = bridge.resolvePackageName(
                packageNameOverride?.takeIf { it.isNotBlank() } ?: defaultPackageName,
            )
            if (!newSession) {
                store.currentSession()
                    ?.takeIf {
                        it.packageName == packageName &&
                            it.projectRoot == projectRoot &&
                            it.status != SessionStatusDto.CLOSED
                    }
                    ?.let { return@synchronized it }
                store.listSessions(packageName = packageName)
                    .sessions
                    .firstOrNull { it.projectRoot == projectRoot }
                    ?.let { return@synchronized store.openExistingSession(it.sessionId) }
            }
            store.openSession(packageName = packageName, projectRoot = projectRoot)
        }

    fun currentSession(): SessionDto =
        store.currentSession() ?: openSession(null)

    fun currentSessionOrNull(): SessionDto? =
        store.currentSession()

    fun requireCurrentSession(): SessionDto =
        currentSessionOrNull() ?: throw FeedbackSessionException("NO_ACTIVE_SESSION: Start a feedback session first")

    fun getSession(sessionId: String): SessionDto = store.getSession(sessionId)

    fun listSessions(packageNameOverride: String? = null, includeClosed: Boolean = false): FeedbackSessionList {
        val packageName = packageNameOverride
            ?.takeIf { it.isNotBlank() }
            ?.let { bridge.resolvePackageName(it) }
        return store.listSessions(packageName = packageName, includeClosed = includeClosed)
    }

    fun closeSession(sessionId: String): SessionDto = store.closeSession(sessionId)

    fun devices(): List<AdbDevice> = connectionService.devices()

    fun selectedDeviceSerial(): String? = connectionService.selectedDeviceSerial()

    fun selectDevice(serial: String) = connectionService.selectDevice(serial)

    fun disconnectDevice() = connectionService.disconnectDevice()

    suspend fun heartbeat(sessionId: String): JsonObject {
        val session = store.getSession(sessionId)
        return bridge.heartbeat(session.packageName)
    }

    suspend fun heartbeatForCurrentSession(): JsonObject {
        val session = currentSessionOrNull() ?: transientConsoleSession()
        return bridge.heartbeat(session.packageName)
    }

    suspend fun connectionStatus(): ConsoleConnectionStatus =
        connectionService.connectionStatus(currentSessionOrNull() ?: transientConsoleSession())

    suspend fun launchAppForCurrentSession(): ConsoleConnectionStatus =
        connectionService.launchAppForSession(currentSessionOrNull() ?: transientConsoleSession())

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

    suspend fun capturePreview(sessionId: String): FeedbackPreviewSnapshot =
        previewCaptureService.capturePreview(store.getSession(sessionId))

    fun previewScreenshotFile(sessionId: String, previewId: String): File =
        previewCaptureService.previewScreenshotFile(sessionId, previewId)

    suspend fun navigate(sessionId: String, request: FeedbackNavigationRequest): FeedbackNavigationResult {
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

    fun addAreaFeedback(
        sessionId: String,
        screenId: String,
        bounds: FixThisRect,
        comment: String,
    ): AnnotationDto =
        feedbackDraftService.addAreaFeedback(
            sessionId = sessionId,
            screenId = screenId,
            bounds = bounds,
            comment = comment,
        )

    suspend fun addFeedbackItem(
        sessionId: String,
        screenId: String,
        targetType: FeedbackTargetType,
        bounds: FixThisRect,
        nodeUid: String?,
        comment: String,
    ): AnnotationDto =
        feedbackDraftService.addFeedbackItem(
            sessionId = sessionId,
            screenId = screenId,
            targetType = targetType,
            bounds = bounds,
            nodeUid = nodeUid,
            comment = comment,
        )

    fun savePreviewFeedbackItems(
        sessionId: String,
        previewId: String,
        items: List<AnnotationDraftDto>,
        fallbackScreen: SnapshotDto? = null,
    ): SessionDto =
        feedbackDraftService.savePreviewFeedbackItems(
            sessionId = sessionId,
            previewId = previewId,
            items = items,
            fallbackScreen = fallbackScreen,
            allowBlankComments = true,
        )

    fun clearDraftItems(sessionId: String): SessionDto =
        feedbackDraftService.clearDraftItems(sessionId)

    fun deleteScreen(sessionId: String, screenId: String): SessionDto =
        store.deleteScreen(sessionId, screenId)

    fun sendDraftToAgent(sessionId: String, prompt: String? = null): SessionDto =
        feedbackDraftService.sendDraftToAgent(sessionId, prompt)

    fun markReadyForAgent(sessionId: String): SessionDto =
        feedbackDraftService.markReadyForAgent(sessionId)

    fun resolveFeedback(sessionId: String, itemId: String, status: AnnotationStatusDto, summary: String?): AnnotationDto =
        store.updateItemStatus(sessionId, itemId, status, summary)

    fun updateDraftFeedback(
        sessionId: String,
        itemId: String,
        label: String?,
        severity: AnnotationSeverityDto?,
        comment: String?,
        status: AnnotationStatusDto?,
    ): SessionDto =
        store.updateDraftItem(
            sessionId = sessionId,
            itemId = itemId,
            label = label,
            severity = severity,
            comment = comment,
            status = status,
        )

    fun deleteDraftFeedback(sessionId: String, itemId: String): SessionDto =
        store.deleteDraftItem(sessionId, itemId)

    private companion object {
        const val MaxRetainedPreviews = 3
    }

    private fun transientConsoleSession(): SessionDto =
        SessionDto(
            sessionId = "",
            packageName = bridge.resolvePackageName(defaultPackageName),
            projectRoot = projectRoot,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
        )
}
