package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.console.AnnotationDraftDto
import io.beyondwin.fixthis.mcp.console.FeedbackTargetType

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
        frozenFingerprint: String? = null,
        currentFingerprint: String? = null,
        forceMismatchOverride: Boolean = false,
    ): SessionDto = draftService.savePreviewFeedbackItems(
        sessionId = sessionId,
        previewId = previewId,
        items = items,
        fallbackScreen = fallbackScreen,
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
        frozenFingerprint: String? = null,
        currentFingerprint: String? = null,
        forceMismatchOverride: Boolean = false,
    ): PreviewFeedbackSaveResult = draftService.savePreviewFeedbackItemsWithMetadata(
        sessionId = sessionId,
        previewId = previewId,
        items = items,
        fallbackScreen = fallbackScreen,
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
    ): PreviewFeedbackSaveReservation = draftService.preparePreviewFeedbackSave(
        sessionId = sessionId,
        previewId = previewId,
        items = items,
        fallbackScreen = fallbackScreen,
        allowBlankComments = true,
    )

    internal fun commitPreviewFeedbackSave(
        reservation: PreviewFeedbackSaveReservation,
        frozenFingerprint: String? = null,
        currentFingerprint: String? = null,
        forceMismatchOverride: Boolean = false,
        frozenFingerprintSource: String = "client",
        clientFrozenFingerprintMismatched: Boolean = false,
    ): SessionDto = draftService.commitPreviewFeedbackSave(
        reservation = reservation,
        frozenFingerprint = frozenFingerprint,
        currentFingerprint = currentFingerprint,
        forceMismatchOverride = forceMismatchOverride,
        frozenFingerprintSource = frozenFingerprintSource,
        clientFrozenFingerprintMismatched = clientFrozenFingerprintMismatched,
    )

    internal fun commitPreviewFeedbackSaveWithMetadata(
        reservation: PreviewFeedbackSaveReservation,
        frozenFingerprint: String? = null,
        currentFingerprint: String? = null,
        forceMismatchOverride: Boolean = false,
        frozenFingerprintSource: String = "client",
        clientFrozenFingerprintMismatched: Boolean = false,
    ): PreviewFeedbackSaveResult = draftService.commitPreviewFeedbackSaveWithMetadata(
        reservation = reservation,
        frozenFingerprint = frozenFingerprint,
        currentFingerprint = currentFingerprint,
        forceMismatchOverride = forceMismatchOverride,
        frozenFingerprintSource = frozenFingerprintSource,
        clientFrozenFingerprintMismatched = clientFrozenFingerprintMismatched,
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
    ): AnnotationDto = store.updateItemStatus(sessionId, itemId, status, summary)

    fun claimFeedback(
        sessionId: String,
        itemId: String,
        agentNote: String?,
    ): AnnotationDto = store.claimFeedback(sessionId, itemId, agentNote)

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
