package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.console.AnnotationDraftDto
import io.beyondwin.fixthis.mcp.console.FeedbackTargetType

/**
 * Owns annotation CRUD and status changes.
 *
 * Split out of `FeedbackSessionService` (CH-4): add/update/delete/resolve/claim of
 * `AnnotationDto` rows go through this class. Heavy lifting (preview save, evidence
 * binding) is still done by `FeedbackDraftService`; this class is the narrow
 * interface seen by the façade and HTTP routes.
 */
class AnnotationRepository(
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

    fun clearDraftItems(sessionId: String): SessionDto = draftService.clearDraftItems(sessionId)

    fun deleteScreen(sessionId: String, screenId: String): SessionDto = store.deleteScreen(sessionId, screenId)

    fun resolveFeedback(
        sessionId: String,
        itemId: String,
        status: AnnotationStatusDto,
        summary: String?,
    ): AnnotationDto = store.updateItemStatus(sessionId, itemId, status, summary)

    fun claimFeedback(sessionId: String, itemId: String, agentNote: String?): AnnotationDto = store.claimFeedback(sessionId, itemId, agentNote)

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

    fun deleteDraftFeedback(sessionId: String, itemId: String): SessionDto = store.deleteDraftItem(sessionId, itemId)

    fun markItemsHandedOff(sessionId: String, itemIds: List<String>): SessionDto = store.markItemsHandedOff(sessionId, itemIds)
}
