package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatus
import io.github.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.github.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.usecase.feedback.ClaimAnnotationCommand
import io.github.beyondwin.fixthis.compose.core.usecase.feedback.ClaimAnnotationUseCase
import io.github.beyondwin.fixthis.compose.core.usecase.feedback.ResolveAnnotationCommand
import io.github.beyondwin.fixthis.compose.core.usecase.feedback.ResolveAnnotationUseCase
import io.github.beyondwin.fixthis.mcp.console.AnnotationDraftDto
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.github.beyondwin.fixthis.mcp.session.domain.McpSessionRepository
import kotlinx.coroutines.runBlocking

/**
 * Owns MCP annotation workflow operations over DTO-backed sessions.
 *
 * This is intentionally not the core `AnnotationRepository` port. It coordinates
 * draft preview saves, target evidence, and status transitions at the MCP
 * boundary.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class AnnotationWorkflow(
    private val store: FeedbackSessionStore,
    private val draftService: FeedbackDraftService,
) {
    private val sessions = McpSessionRepository(store)
    private val resolveAnnotation = ResolveAnnotationUseCase(sessions, clock = { System.currentTimeMillis() })
    private val claimAnnotation = ClaimAnnotationUseCase(sessions, clock = { System.currentTimeMillis() })

    fun addAreaFeedback(
        sessionId: String,
        screenId: String,
        bounds: FixThisRect,
        comment: String,
    ): AnnotationDto = draftService.addAreaFeedback(
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
    ): AnnotationDto = draftService.addFeedbackItem(
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
        workspaceId: String? = null,
        frozenFingerprint: String? = null,
        currentFingerprint: String? = null,
        forceMismatchOverride: Boolean = false,
    ): SessionDto = draftService.savePreviewFeedbackItems(
        sessionId = sessionId,
        previewId = previewId,
        items = items,
        fallbackScreen = fallbackScreen,
        workspaceId = workspaceId,
        allowBlankComments = true,
        frozenFingerprint = frozenFingerprint,
        currentFingerprint = currentFingerprint,
        forceMismatchOverride = forceMismatchOverride,
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
    ): PreviewFeedbackSaveResult = draftService.savePreviewFeedbackItemsWithMetadata(
        sessionId = sessionId,
        previewId = previewId,
        items = items,
        fallbackScreen = fallbackScreen,
        workspaceId = workspaceId,
        allowBlankComments = true,
        frozenFingerprint = frozenFingerprint,
        currentFingerprint = currentFingerprint,
        forceMismatchOverride = forceMismatchOverride,
    )

    internal fun preparePreviewFeedbackSave(
        sessionId: String,
        previewId: String,
        items: List<AnnotationDraftDto>,
        fallbackScreen: SnapshotDto? = null,
        workspaceId: String? = null,
    ): PreviewFeedbackSaveReservation = draftService.preparePreviewFeedbackSave(
        sessionId = sessionId,
        previewId = previewId,
        items = items,
        fallbackScreen = fallbackScreen,
        workspaceId = workspaceId,
        allowBlankComments = true,
    )

    internal fun commitPreviewFeedbackSave(
        reservation: PreviewFeedbackSaveReservation,
        fingerprintCheck: PreviewSaveFingerprintCheck = PreviewSaveFingerprintCheck(),
    ): SessionDto = draftService.commitPreviewFeedbackSave(
        reservation = reservation,
        fingerprintCheck = fingerprintCheck,
    )

    internal fun commitPreviewFeedbackSaveWithMetadata(
        reservation: PreviewFeedbackSaveReservation,
        fingerprintCheck: PreviewSaveFingerprintCheck = PreviewSaveFingerprintCheck(),
    ): PreviewFeedbackSaveResult = draftService.commitPreviewFeedbackSaveWithMetadata(
        reservation = reservation,
        fingerprintCheck = fingerprintCheck,
    )

    internal fun cancelPreviewFeedbackSave(reservation: PreviewFeedbackSaveReservation) {
        draftService.cancelPreviewFeedbackSave(reservation)
    }

    fun clearDraftItems(sessionId: String): SessionDto = draftService.clearDraftItems(sessionId)

    fun deleteScreen(sessionId: String, screenId: String): SessionDto = store.deleteScreen(sessionId, screenId)

    fun resolveFeedback(
        sessionId: String,
        itemId: String,
        status: AnnotationStatusDto,
        summary: String?,
    ): AnnotationDto = runBlocking {
        val updated = resolveAnnotation(
            ResolveAnnotationCommand(
                sessionId = SessionId(sessionId),
                annotationId = AnnotationId(itemId),
                status = status.toDomainResolutionStatus(),
                summary = summary,
            ),
        )
        updated.annotations.first { it.id.value == itemId }.toAnnotationDto()
    }

    fun claimFeedback(
        sessionId: String,
        itemId: String,
        agentNote: String?,
    ): AnnotationDto = runBlocking {
        val updated = claimAnnotation(
            ClaimAnnotationCommand(
                sessionId = SessionId(sessionId),
                annotationId = AnnotationId(itemId),
                agentNote = agentNote,
            ),
        )
        updated.annotations.first { it.id.value == itemId }.toAnnotationDto()
    }

    fun updateDraftFeedback(
        sessionId: String,
        itemId: String,
        label: String?,
        severity: AnnotationSeverityDto?,
        comment: String?,
        status: AnnotationStatusDto?,
    ): SessionDto = store.updateDraftItem(
        sessionId = sessionId,
        itemId = itemId,
        label = label,
        severity = severity,
        comment = comment,
        status = status,
    )

    fun deleteDraftFeedback(
        sessionId: String,
        itemId: String,
    ): SessionDto = store.deleteDraftItem(sessionId, itemId)

    fun markItemsHandedOff(
        sessionId: String,
        itemIds: List<String>,
    ): SessionDto = store.markItemsHandedOff(sessionId, itemIds)
}

private fun AnnotationStatusDto.toDomainResolutionStatus(): AnnotationStatus = when (this) {
    AnnotationStatusDto.RESOLVED -> AnnotationStatus.RESOLVED
    AnnotationStatusDto.NEEDS_CLARIFICATION -> AnnotationStatus.NEEDS_CLARIFICATION
    AnnotationStatusDto.WONT_FIX -> AnnotationStatus.WONT_FIX
    AnnotationStatusDto.OPEN,
    AnnotationStatusDto.READY,
    AnnotationStatusDto.IN_PROGRESS,
    -> throw IllegalArgumentException("Agent resolution status is not allowed: $this")
}
