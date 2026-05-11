package io.beyondwin.fixthis.mcp.session

// Façade exceeds 60-line target: connection + handoff pass-throughs not split per plan
import io.beyondwin.fixthis.cli.AdbDevice
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.console.AnnotationDraftDto
import io.beyondwin.fixthis.mcp.console.ConsoleConnectionStatus
import io.beyondwin.fixthis.mcp.console.FeedbackPreviewSnapshot
import io.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.beyondwin.fixthis.mcp.tools.FixThisBridge
import java.io.File
import kotlinx.serialization.json.JsonObject

data class SendDraftToAgentResult(val session: SessionDto, val prompt: String)

/**
 * Thin façade over [FeedbackSessionRegistry], [AnnotationRepository], and
 * [EvidenceCoordinator]. Preserves the existing public API used by ~10 caller
 * files (HTTP routes, MCP tools) so the CH-4 split doesn't ripple into them.
 *
 * Connection and handoff pass-throughs remain on the façade per the plan.
 */
class FeedbackSessionService(
    private val bridge: FixThisBridge,
    private val store: FeedbackSessionStore = FeedbackSessionStore(),
    private val projectRoot: String,
    private val defaultPackageName: String? = null,
    previewCache: PreviewSnapshotCache = PreviewSnapshotCache(MaxRetainedPreviews),
    sourceIndexRegistry: SourceIndexRegistry = SourceIndexRegistry(),
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
    private val registry = FeedbackSessionRegistry(
        bridge = bridge,
        store = store,
        projectRoot = projectRoot,
        defaultPackageName = defaultPackageName,
    )
    private val annotations = AnnotationRepository(
        store = store,
        draftService = feedbackDraftService,
    )
    private val evidence = EvidenceCoordinator(
        bridge = bridge,
        store = store,
        previewCaptureService = previewCaptureService,
    )

    // --- Session lifecycle (delegates to FeedbackSessionRegistry) ---

    fun openSession(
        packageNameOverride: String?,
        sessionId: String? = null,
        newSession: Boolean = false,
    ): SessionDto = registry.openSession(packageNameOverride, sessionId, newSession)

    fun currentSession(): SessionDto = registry.currentSession()

    fun currentSessionOrNull(): SessionDto? = registry.currentSessionOrNull()

    fun requireCurrentSession(): SessionDto = registry.requireCurrentSession()

    fun getSession(sessionId: String): SessionDto = registry.getSession(sessionId)

    fun findSession(sessionId: String): SessionDto? = registry.findSession(sessionId)

    fun listSessions(packageNameOverride: String? = null, includeClosed: Boolean = false): FeedbackSessionList =
        registry.listSessions(packageNameOverride, includeClosed)

    fun closeSession(sessionId: String): SessionDto = registry.closeSession(sessionId)

    // --- Device / connection pass-throughs ---

    fun devices(): List<AdbDevice> = connectionService.devices()

    fun selectedDeviceSerial(): String? = connectionService.selectedDeviceSerial()

    fun selectDevice(serial: String) = connectionService.selectDevice(serial)

    fun disconnectDevice() = connectionService.disconnectDevice()

    suspend fun heartbeat(sessionId: String): JsonObject {
        val session = registry.getSession(sessionId)
        return bridge.heartbeat(session.packageName)
    }

    suspend fun heartbeatForCurrentSession(): JsonObject {
        val session = registry.currentSessionOrNull() ?: registry.transientConsoleSession()
        return bridge.heartbeat(session.packageName)
    }

    suspend fun connectionStatus(): ConsoleConnectionStatus =
        connectionService.connectionStatus(registry.currentSessionOrNull() ?: registry.transientConsoleSession())

    suspend fun launchAppForCurrentSession(): ConsoleConnectionStatus =
        connectionService.launchAppForSession(registry.currentSessionOrNull() ?: registry.transientConsoleSession())

    // --- Evidence capture (delegates to EvidenceCoordinator) ---

    suspend fun captureScreen(sessionId: String): SnapshotDto = evidence.captureScreen(sessionId)

    suspend fun capturePreview(sessionId: String): FeedbackPreviewSnapshot =
        evidence.capturePreview(sessionId)

    fun previewScreenshotFile(sessionId: String, previewId: String): File =
        evidence.previewScreenshotFile(sessionId, previewId)

    suspend fun navigate(sessionId: String, request: FeedbackNavigationRequest): FeedbackNavigationResult =
        evidence.navigate(sessionId, request)

    // --- Annotation CRUD (delegates to AnnotationRepository) ---

    fun addAreaFeedback(
        sessionId: String,
        screenId: String,
        bounds: FixThisRect,
        comment: String,
    ): AnnotationDto = annotations.addAreaFeedback(sessionId, screenId, bounds, comment)

    suspend fun addFeedbackItem(
        sessionId: String,
        screenId: String,
        targetType: FeedbackTargetType,
        bounds: FixThisRect,
        nodeUid: String?,
        comment: String,
    ): AnnotationDto = annotations.addFeedbackItem(sessionId, screenId, targetType, bounds, nodeUid, comment)

    fun savePreviewFeedbackItems(
        sessionId: String,
        previewId: String,
        items: List<AnnotationDraftDto>,
        fallbackScreen: SnapshotDto? = null,
    ): SessionDto = annotations.savePreviewFeedbackItems(sessionId, previewId, items, fallbackScreen)

    fun clearDraftItems(sessionId: String): SessionDto = annotations.clearDraftItems(sessionId)

    fun deleteScreen(sessionId: String, screenId: String): SessionDto =
        annotations.deleteScreen(sessionId, screenId)

    fun resolveFeedback(
        sessionId: String,
        itemId: String,
        status: AnnotationStatusDto,
        summary: String?,
    ): AnnotationDto = annotations.resolveFeedback(sessionId, itemId, status, summary)

    fun claimFeedback(sessionId: String, itemId: String, agentNote: String?): AnnotationDto =
        annotations.claimFeedback(sessionId, itemId, agentNote)

    fun updateDraftFeedback(
        sessionId: String,
        itemId: String,
        label: String?,
        severity: AnnotationSeverityDto?,
        comment: String?,
        status: AnnotationStatusDto?,
    ): SessionDto = annotations.updateDraftFeedback(sessionId, itemId, label, severity, comment, status)

    fun deleteDraftFeedback(sessionId: String, itemId: String): SessionDto =
        annotations.deleteDraftFeedback(sessionId, itemId)

    fun markItemsHandedOff(sessionId: String, itemIds: List<String>): SessionDto =
        annotations.markItemsHandedOff(sessionId, itemIds)

    // --- Handoff (kept on façade; uses registry for session lookup) ---

    fun sendDraftToAgent(sessionId: String, itemIds: List<String>): SendDraftToAgentResult {
        require(itemIds.isNotEmpty()) { "itemIds must not be empty" }
        val session = registry.getSession(sessionId)
        val prompt = CompactHandoffRenderer.render(session, itemIds = itemIds)
        val updated = feedbackDraftService.sendDraftToAgent(
            sessionId = sessionId,
            prompt = prompt,
            targetItemIds = itemIds,
        )
        return SendDraftToAgentResult(session = updated, prompt = prompt)
    }

    /**
     * Sends all current draft items to agent — bypasses CompactHandoffRenderer.
     * Production HTTP path uses sendDraftToAgent(sessionId, itemIds) above; this overload
     * exists only for legacy MCP tool callsites and test setup. Do not call from new code.
     */
    @Deprecated(
        "Test/MCP-tool only. Production code must use sendDraftToAgent(sessionId, itemIds) " +
            "to keep CompactHandoffRenderer as the single source of truth for handoff markdown.",
        ReplaceWith("sendDraftToAgent(sessionId, itemIds)"),
    )
    fun sendDraftToAgent(sessionId: String): SessionDto =
        feedbackDraftService.sendDraftToAgent(sessionId)

    fun markReadyForAgent(sessionId: String): SessionDto =
        feedbackDraftService.markReadyForAgent(sessionId)

    private companion object {
        const val MaxRetainedPreviews = 3
    }
}
