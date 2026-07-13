package io.github.beyondwin.fixthis.mcp.session

// Façade exceeds 60-line target: connection + handoff pass-throughs not split per plan
import io.github.beyondwin.fixthis.cli.AdbDevice
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.console.AnnotationDraftDto
import io.github.beyondwin.fixthis.mcp.console.ConsoleConnectionStatus
import io.github.beyondwin.fixthis.mcp.console.FeedbackPreviewSnapshot
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.github.beyondwin.fixthis.mcp.session.connection.ConsoleConnectionService
import io.github.beyondwin.fixthis.mcp.session.draft.AnnotationWorkflow
import io.github.beyondwin.fixthis.mcp.session.draft.EvidenceCoordinator
import io.github.beyondwin.fixthis.mcp.session.draft.FeedbackDraftService
import io.github.beyondwin.fixthis.mcp.session.draft.PreviewFeedbackSaveResult
import io.github.beyondwin.fixthis.mcp.session.draft.PreviewSaveFingerprintCheck
import io.github.beyondwin.fixthis.mcp.session.draft.serverFrozenFingerprint
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationSeverityDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.FeedbackNavigationRequest
import io.github.beyondwin.fixthis.mcp.session.dto.FeedbackNavigationResult
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.handoff.CompactHandoffRenderer
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionList
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionRegistry
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewCacheRetentionPolicy
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewCaptureService
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewSnapshotCache
import io.github.beyondwin.fixthis.mcp.session.preview.ScreenshotArtifactPromoter
import io.github.beyondwin.fixthis.mcp.session.runtime.FileRuntimeEvidenceArtifactStore
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceAvailabilityService
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceCaptureCoordinator
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceCaptureDependencies
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceCaptureRequest
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceCaptureResult
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceHandoffService
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePolicy
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceRedactor
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceService
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceSummarizer
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceType
import io.github.beyondwin.fixthis.mcp.session.runtime.SendDraftToAgentWithRuntimeEvidenceResult
import io.github.beyondwin.fixthis.mcp.session.source.SourceIndexRegistry
import io.github.beyondwin.fixthis.mcp.session.target.TargetEvidenceService
import io.github.beyondwin.fixthis.mcp.tools.FixThisBridge
import io.github.beyondwin.fixthis.mcp.tools.RuntimeEvidenceBridge
import io.github.beyondwin.fixthis.mcp.tools.UnavailableRuntimeEvidenceBridge
import kotlinx.serialization.json.JsonObject
import java.io.File

data class SendDraftToAgentResult(val session: SessionDto, val prompt: String)

internal data class PreviewFeedbackLiveSaveRequest(
    val sessionId: String,
    val previewId: String,
    val workspaceId: String? = null,
    val items: List<AnnotationDraftDto>,
    val fallbackScreen: SnapshotDto? = null,
    val fingerprintCheck: PreviewFeedbackFingerprintCheck = PreviewFeedbackFingerprintCheck(),
)

internal data class PreviewFeedbackFingerprintCheck(
    val frozenFingerprint: String? = null,
    val forceMismatchOverride: Boolean = false,
)

/**
 * Thin façade over [FeedbackSessionRegistry], [AnnotationWorkflow], and
 * [EvidenceCoordinator]. Preserves the existing public API used by ~10 caller
 * files (HTTP routes, MCP tools) so the CH-4 split doesn't ripple into them.
 *
 * Connection and handoff pass-throughs remain on the façade per the plan.
 */
@Suppress("LongParameterList")
class FeedbackSessionService(
    private val bridge: FixThisBridge,
    private val store: FeedbackSessionStore = FeedbackSessionStore(),
    private val projectRoot: String,
    private val defaultPackageName: String? = null,
    previewCache: PreviewSnapshotCache = PreviewSnapshotCache(MaxRetainedPreviews),
    previewCacheRetentionPolicy: PreviewCacheRetentionPolicy = PreviewCacheRetentionPolicy(),
    sourceIndexRegistry: SourceIndexRegistry = SourceIndexRegistry(),
    runtimeEvidenceBridge: RuntimeEvidenceBridge = bridge as? RuntimeEvidenceBridge
        ?: UnavailableRuntimeEvidenceBridge(),
) {
    private val configuredProjectRoot = File(projectRoot).canonicalFile
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
        previewCacheRetentionPolicy = previewCacheRetentionPolicy,
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
    private val annotations = AnnotationWorkflow(
        store = store,
        draftService = feedbackDraftService,
    )
    private val evidence = EvidenceCoordinator(
        bridge = bridge,
        store = store,
        previewCaptureService = previewCaptureService,
    )
    private val runtimeEvidenceService = RuntimeEvidenceService(
        store = store,
        idGenerator = { store.nextId() },
    )
    private val runtimeEvidenceAvailability = RuntimeEvidenceAvailabilityService(configuredProjectRoot)
    private val runtimeEvidenceRedactor = RuntimeEvidenceRedactor()
    private val runtimeEvidenceCoordinator = RuntimeEvidenceCaptureCoordinator(
        bridge = runtimeEvidenceBridge,
        store = store,
        projectRoot = configuredProjectRoot,
        artifactStore = FileRuntimeEvidenceArtifactStore(configuredProjectRoot, runtimeEvidenceRedactor),
        dependencies = RuntimeEvidenceCaptureDependencies(
            redactor = runtimeEvidenceRedactor,
            summarizer = RuntimeEvidenceSummarizer(runtimeEvidenceRedactor),
            idGenerator = { store.nextId() },
        ),
    )
    private val runtimeEvidenceHandoffService = RuntimeEvidenceHandoffService(
        readSession = { sessionId -> materialize(registry.getSession(sessionId)) },
        collect = runtimeEvidenceCoordinator::collect,
        render = { session, itemIds -> CompactHandoffRenderer.render(session, itemIds = itemIds) },
        markSent = { sessionId, prompt, itemIds ->
            materialize(
                feedbackDraftService.sendDraftToAgent(
                    sessionId = sessionId,
                    prompt = prompt,
                    targetItemIds = itemIds,
                ),
            )
        },
    )

    // --- Session lifecycle (delegates to FeedbackSessionRegistry) ---

    fun openSession(
        packageNameOverride: String?,
        sessionId: String? = null,
        newSession: Boolean = false,
    ): SessionDto = materialize(registry.openSession(packageNameOverride, sessionId, newSession))

    fun currentSession(): SessionDto = materialize(registry.currentSession())

    fun currentSessionOrNull(): SessionDto? = registry.currentSessionOrNull()?.let(::materialize)

    fun requireCurrentSession(): SessionDto = materialize(registry.requireCurrentSession())

    fun getSession(sessionId: String): SessionDto = materialize(registry.getSession(sessionId))

    fun findSession(sessionId: String): SessionDto? = registry.findSession(sessionId)?.let(::materialize)

    fun listSessions(packageNameOverride: String? = null, includeClosed: Boolean = false): FeedbackSessionList = registry.listSessions(packageNameOverride, includeClosed)

    fun closeSession(sessionId: String): SessionDto = materialize(registry.closeSession(sessionId))

    suspend fun refreshSourceEvidenceForHandoff(session: SessionDto): SessionDto {
        val screenById = session.screens.associateBy { it.screenId }
        val sourceScreen = session.screens.firstOrNull { it.sourceIndexAvailable }
        val sourceIndex = sourceScreen?.let {
            targetEvidenceService.readSourceIndexOrNull(session.packageName, it)
        }
        return if (sourceIndex != null && session.items.any { it.sourceCandidates.isNotEmpty() }) {
            materialize(
                session.copy(
                    items = session.items.map { item ->
                        val screen = screenById[item.screenId] ?: return@map item
                        targetEvidenceService.refreshSourceEvidence(item, screen, sourceIndex)
                    },
                ),
            )
        } else {
            materialize(session)
        }
    }

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

    suspend fun connectionStatus(): ConsoleConnectionStatus = connectionService.connectionStatus(registry.currentSessionOrNull() ?: registry.transientConsoleSession())

    suspend fun launchAppForCurrentSession(): ConsoleConnectionStatus = connectionService.launchAppForSession(registry.currentSessionOrNull() ?: registry.transientConsoleSession())

    // --- Evidence capture (delegates to EvidenceCoordinator) ---

    suspend fun captureScreen(sessionId: String): SnapshotDto = evidence.captureScreen(sessionId)

    suspend fun capturePreview(sessionId: String): FeedbackPreviewSnapshot = evidence.capturePreview(sessionId)

    fun previewScreenshotFile(sessionId: String, previewId: String): File = evidence.previewScreenshotFile(sessionId, previewId)

    suspend fun navigate(sessionId: String, request: FeedbackNavigationRequest): FeedbackNavigationResult = evidence.navigate(sessionId, request)

    // --- Annotation CRUD (delegates to AnnotationWorkflow) ---

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
        workspaceId: String? = null,
        frozenFingerprint: String? = null,
        currentFingerprint: String? = null,
        forceMismatchOverride: Boolean = false,
    ): SessionDto = materialize(
        annotations.savePreviewFeedbackItems(
            sessionId = sessionId,
            previewId = previewId,
            items = items,
            fallbackScreen = fallbackScreen,
            workspaceId = workspaceId,
            frozenFingerprint = frozenFingerprint,
            currentFingerprint = currentFingerprint,
            forceMismatchOverride = forceMismatchOverride,
        ),
    )

    internal fun savePreviewFeedbackItemsWithMetadata(
        sessionId: String,
        previewId: String,
        items: List<AnnotationDraftDto>,
        fallbackScreen: SnapshotDto? = null,
        workspaceId: String? = null,
        frozenFingerprint: String? = null,
        currentFingerprint: String? = null,
        forceMismatchOverride: Boolean = false,
    ): PreviewFeedbackSaveResult = annotations.savePreviewFeedbackItemsWithMetadata(
        sessionId = sessionId,
        previewId = previewId,
        items = items,
        fallbackScreen = fallbackScreen,
        workspaceId = workspaceId,
        frozenFingerprint = frozenFingerprint,
        currentFingerprint = currentFingerprint,
        forceMismatchOverride = forceMismatchOverride,
    ).materialized()

    internal suspend fun savePreviewFeedbackItemsWithLiveFingerprintMetadata(
        request: PreviewFeedbackLiveSaveRequest,
    ): PreviewFeedbackSaveResult {
        val session = registry.getSession(request.sessionId)
        val reservation = annotations.preparePreviewFeedbackSave(
            sessionId = request.sessionId,
            previewId = request.previewId,
            workspaceId = request.workspaceId,
            items = request.items,
            fallbackScreen = request.fallbackScreen,
        )
        var recaptureCompleted = false
        val currentScreen = try {
            previewCaptureService.captureCurrentScreenForFingerprint(session)
                .also { recaptureCompleted = true }
        } finally {
            if (!recaptureCompleted) {
                annotations.cancelPreviewFeedbackSave(reservation)
            }
        }
        val serverFrozenFingerprint = reservation.serverFrozenFingerprint()
        val clientFrozenFingerprint = request.fingerprintCheck.frozenFingerprint
        return annotations.commitPreviewFeedbackSaveWithMetadata(
            reservation = reservation,
            fingerprintCheck = PreviewSaveFingerprintCheck(
                frozenFingerprint = serverFrozenFingerprint.value,
                currentFingerprint = currentScreen.fingerprint,
                forceMismatchOverride = request.fingerprintCheck.forceMismatchOverride,
                frozenFingerprintSource = serverFrozenFingerprint.source,
                clientFrozenFingerprintMismatched = clientFrozenFingerprint != null &&
                    clientFrozenFingerprint != serverFrozenFingerprint.value,
            ),
        ).materialized()
    }

    fun clearDraftItems(sessionId: String): SessionDto = materialize(annotations.clearDraftItems(sessionId))

    fun deleteScreen(sessionId: String, screenId: String): SessionDto = materialize(annotations.deleteScreen(sessionId, screenId))

    fun resolveFeedback(
        sessionId: String,
        itemId: String,
        status: AnnotationStatusDto,
        summary: String?,
    ): AnnotationDto = annotations.resolveFeedback(sessionId, itemId, status, summary)

    fun claimFeedback(sessionId: String, itemId: String, agentNote: String?): AnnotationDto = annotations.claimFeedback(sessionId, itemId, agentNote)

    fun updateDraftFeedback(
        sessionId: String,
        itemId: String,
        label: String?,
        severity: AnnotationSeverityDto?,
        comment: String?,
        status: AnnotationStatusDto?,
    ): SessionDto = materialize(annotations.updateDraftFeedback(sessionId, itemId, label, severity, comment, status))

    fun deleteDraftFeedback(sessionId: String, itemId: String): SessionDto = materialize(annotations.deleteDraftFeedback(sessionId, itemId))

    fun markItemsHandedOff(sessionId: String, itemIds: List<String>): SessionDto = materialize(annotations.markItemsHandedOff(sessionId, itemIds))

    fun captureRuntimeEvidence(
        sessionId: String,
        itemId: String,
        type: RuntimeEvidenceType,
        summary: String,
        artifactPath: String?,
    ): SessionDto = materialize(
        runtimeEvidenceService.attachManualSummary(
            sessionId = sessionId,
            itemId = itemId,
            type = type,
            summary = summary,
            artifactPath = artifactPath,
        ),
    )

    suspend fun collectRuntimeEvidence(request: RuntimeEvidenceCaptureRequest): RuntimeEvidenceCaptureResult = runtimeEvidenceCoordinator.collect(request)

    fun updateRuntimeEvidencePolicy(sessionId: String, policy: RuntimeEvidencePolicy): SessionDto = materialize(store.updateRuntimeEvidencePolicy(sessionId, policy))

    // --- Handoff (kept on façade; uses registry for session lookup) ---

    fun sendDraftToAgent(sessionId: String, itemIds: List<String>): SendDraftToAgentResult {
        require(itemIds.isNotEmpty()) { "itemIds must not be empty" }
        val session = materialize(registry.getSession(sessionId))
        val prompt = CompactHandoffRenderer.render(session, itemIds = itemIds)
        val updated = feedbackDraftService.sendDraftToAgent(
            sessionId = sessionId,
            prompt = prompt,
            targetItemIds = itemIds,
        )
        return SendDraftToAgentResult(session = materialize(updated), prompt = prompt)
    }

    suspend fun sendDraftToAgentWithRuntimeEvidence(
        sessionId: String,
        itemIds: List<String>,
    ): SendDraftToAgentWithRuntimeEvidenceResult = runtimeEvidenceHandoffService.sendDraftToAgentWithRuntimeEvidence(sessionId, itemIds)

    fun markReadyForAgent(sessionId: String): SessionDto = materialize(feedbackDraftService.markReadyForAgent(sessionId))

    private fun materialize(session: SessionDto): SessionDto = runtimeEvidenceAvailability.materialize(session)

    private fun PreviewFeedbackSaveResult.materialized(): PreviewFeedbackSaveResult = copy(session = materialize(session))

    private companion object {
        const val MaxRetainedPreviews = 3
    }
}
