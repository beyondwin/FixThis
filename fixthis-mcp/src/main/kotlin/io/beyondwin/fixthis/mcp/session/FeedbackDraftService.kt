package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.console.AnnotationDraftDto
import io.beyondwin.fixthis.mcp.console.FeedbackTargetType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class PreviewFeedbackSaveReservation(
    val sessionId: String,
    val previewId: String,
    val items: List<AnnotationDraftDto>,
    val allowBlankComments: Boolean,
    val inFlightKey: String,
    val preview: PreviewRecord,
)

private data class PreviewSaveSlot(
    val inFlightKey: String,
    val cachedPreview: PreviewRecord?,
)

internal data class PreviewFeedbackSaveResult(
    val session: SessionDto,
    val fingerprintUnavailableReason: String?,
)

internal class PreviewFeedbackRequestValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class FeedbackDraftService(
    private val store: FeedbackSessionStore,
    private val previewCache: PreviewSnapshotCache,
    private val targetEvidenceService: TargetEvidenceService,
    private val screenshotArtifactPromoter: ScreenshotArtifactPromoter,
) {
    private val lock = Any()
    private val previewSavesInFlight = mutableSetOf<String>()

    fun addAreaFeedback(
        sessionId: String,
        screenId: String,
        bounds: FixThisRect,
        comment: String,
    ): AnnotationDto {
        val session = store.getSession(sessionId)
        val screen = session.screens.firstOrNull { it.screenId == screenId }
            ?: throw FeedbackSessionException("SCREEN_NOT_FOUND: Unknown screen: $screenId")
        return store.addItem(
            sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(bounds),
                comment = comment,
                status = if (comment.isBlank()) AnnotationStatusDto.OPEN else AnnotationStatusDto.READY,
                targetEvidence = targetEvidenceService.targetEvidenceFor(
                    targetType = FeedbackTargetType.AREA,
                    selectedNode = null,
                    screen = screen,
                    sourceCandidates = emptyList(),
                ),
            ),
        )
    }

    suspend fun addFeedbackItem(
        sessionId: String,
        screenId: String,
        targetType: FeedbackTargetType,
        bounds: FixThisRect,
        nodeUid: String?,
        comment: String,
    ): AnnotationDto {
        val session = store.getSession(sessionId)
        val screen = session.screens.firstOrNull { it.screenId == screenId }
            ?: throw FeedbackSessionException("SCREEN_NOT_FOUND: Unknown screen: $screenId")
        val validatedTarget = targetEvidenceService.validateFeedbackTarget(
            screen = screen,
            targetType = targetType,
            bounds = bounds,
            nodeUid = nodeUid,
            comment = comment,
            allowBlankComment = false,
        )
        val sourceIndex = targetEvidenceService.readSourceIndexOrNull(session.packageName, screen)
        val item = targetEvidenceService.buildFeedbackItem(
            screen = screen,
            sourceIndex = sourceIndex,
            validatedTarget = validatedTarget,
            comment = comment,
            writtenStatus = AnnotationStatusDto.OPEN,
        )
        return store.addItem(sessionId, item)
    }

    fun savePreviewFeedbackItems(
        sessionId: String,
        previewId: String,
        items: List<AnnotationDraftDto>,
        fallbackScreen: SnapshotDto? = null,
        allowBlankComments: Boolean = true,
        frozenFingerprint: String? = null,
        currentFingerprint: String? = null,
        forceMismatchOverride: Boolean = false,
    ): SessionDto = savePreviewFeedbackItemsWithMetadata(
        sessionId = sessionId,
        previewId = previewId,
        items = items,
        fallbackScreen = fallbackScreen,
        allowBlankComments = allowBlankComments,
        frozenFingerprint = frozenFingerprint,
        currentFingerprint = currentFingerprint,
        forceMismatchOverride = forceMismatchOverride,
    ).session

    internal fun savePreviewFeedbackItemsWithMetadata(
        sessionId: String,
        previewId: String,
        items: List<AnnotationDraftDto>,
        fallbackScreen: SnapshotDto? = null,
        allowBlankComments: Boolean = true,
        frozenFingerprint: String? = null,
        currentFingerprint: String? = null,
        forceMismatchOverride: Boolean = false,
    ): PreviewFeedbackSaveResult {
        val reservation = preparePreviewFeedbackSave(
            sessionId = sessionId,
            previewId = previewId,
            items = items,
            fallbackScreen = fallbackScreen,
            allowBlankComments = allowBlankComments,
        )
        return commitPreviewFeedbackSaveWithMetadata(
            reservation = reservation,
            frozenFingerprint = frozenFingerprint,
            currentFingerprint = currentFingerprint,
            forceMismatchOverride = forceMismatchOverride,
        )
    }

    internal fun preparePreviewFeedbackSave(
        sessionId: String,
        previewId: String,
        items: List<AnnotationDraftDto>,
        fallbackScreen: SnapshotDto? = null,
        allowBlankComments: Boolean = true,
    ): PreviewFeedbackSaveReservation {
        requirePreviewFeedbackRequest(items.isNotEmpty()) { "At least one feedback item is required" }
        if (!allowBlankComments) {
            requirePreviewFeedbackRequest(items.none { it.comment.isBlank() }) { "Feedback comment must not be blank" }
        }
        val slot = reservePreviewSave(sessionId, previewId, fallbackScreen)

        return try {
            val preview = slot.cachedPreview ?: run {
                val fallback = checkNotNull(fallbackScreen) {
                    "PREVIEW_NOT_FOUND guard above must have rejected a null fallbackScreen"
                }
                validatePreviewPendingItems(fallback, items, allowBlankComments)
                fallbackPreviewRecord(sessionId, previewId, fallback)
            }
            if (slot.cachedPreview != null) {
                validatePreviewPendingItems(preview.snapshot.screen, items, allowBlankComments)
            }
            PreviewFeedbackSaveReservation(
                sessionId = sessionId,
                previewId = previewId,
                items = items,
                allowBlankComments = allowBlankComments,
                inFlightKey = slot.inFlightKey,
                preview = preview,
            )
        } catch (error: Throwable) {
            releasePreviewSaveReservation(slot.inFlightKey)
            throw error
        }
    }

    private fun reservePreviewSave(
        sessionId: String,
        previewId: String,
        fallbackScreen: SnapshotDto?,
    ): PreviewSaveSlot {
        val inFlightKey = "$sessionId:$previewId"
        return synchronized(lock) {
            val record = previewCache.get(sessionId, previewId)
            if (record == null && fallbackScreen == null) {
                throw FeedbackSessionException("PREVIEW_NOT_FOUND: Unknown preview: $previewId")
            }
            if (!previewSavesInFlight.add(inFlightKey)) {
                throw FeedbackSessionException("PREVIEW_SAVE_IN_PROGRESS: Preview is already being saved: $previewId")
            }
            PreviewSaveSlot(inFlightKey = inFlightKey, cachedPreview = record)
        }
    }

    internal fun commitPreviewFeedbackSave(
        reservation: PreviewFeedbackSaveReservation,
        frozenFingerprint: String? = null,
        currentFingerprint: String? = null,
        forceMismatchOverride: Boolean = false,
    ): SessionDto = commitPreviewFeedbackSaveWithMetadata(
        reservation = reservation,
        frozenFingerprint = frozenFingerprint,
        currentFingerprint = currentFingerprint,
        forceMismatchOverride = forceMismatchOverride,
    ).session

    internal fun commitPreviewFeedbackSaveWithMetadata(
        reservation: PreviewFeedbackSaveReservation,
        frozenFingerprint: String? = null,
        currentFingerprint: String? = null,
        forceMismatchOverride: Boolean = false,
    ): PreviewFeedbackSaveResult {
        return try {
            enforceFingerprintMatch(frozenFingerprint, currentFingerprint, forceMismatchOverride)
            val fingerprintUnavailableReason = fingerprintUnavailableReason(frozenFingerprint, currentFingerprint)
            val eventMetadata = previewSaveEventMetadata(forceMismatchOverride, fingerprintUnavailableReason)
            val preview = reservation.preview
            val feedbackItems = reservation.items.map { pending ->
                targetEvidenceService.buildFeedbackItem(
                    screen = preview.snapshot.screen,
                    sourceIndex = preview.sourceIndex,
                    targetType = pending.targetType,
                    bounds = pending.bounds,
                    nodeUid = pending.nodeUid,
                    comment = pending.comment,
                    allowBlankComment = reservation.allowBlankComments,
                    writtenStatus = pending.status,
                    missingNodeContext = "preview",
                ).copy(
                    label = pending.label?.takeIf { it.isNotBlank() },
                    severity = pending.severity,
                )
            }
            val persistedScreen = screenshotArtifactPromoter.promote(
                projectRoot = preview.projectRoot,
                sessionId = reservation.sessionId,
                screen = preview.snapshot.screen,
            )
            val updated = store.addScreenWithItems(
                reservation.sessionId,
                persistedScreen,
                feedbackItems,
                eventMetadata = eventMetadata,
            )
            val removedPreview = synchronized(lock) {
                previewSavesInFlight.remove(reservation.inFlightKey)
                previewCache.remove(reservation.sessionId, reservation.previewId)
            }
            removedPreview?.deletePreviewCacheDirectory()
            PreviewFeedbackSaveResult(updated, fingerprintUnavailableReason)
        } catch (error: Throwable) {
            cancelPreviewFeedbackSave(reservation)
            throw error
        }
    }

    internal fun cancelPreviewFeedbackSave(reservation: PreviewFeedbackSaveReservation) {
        releasePreviewSaveReservation(reservation.inFlightKey)
    }

    private fun releasePreviewSaveReservation(inFlightKey: String) {
        synchronized(lock) {
            previewSavesInFlight.remove(inFlightKey)
        }
    }

    private fun enforceFingerprintMatch(
        frozenFingerprint: String?,
        currentFingerprint: String?,
        forceMismatchOverride: Boolean,
    ) {
        if (forceMismatchOverride || frozenFingerprint == null || currentFingerprint == null) return
        if (frozenFingerprint != currentFingerprint) {
            throw ScreenFingerprintMismatch(frozenFingerprint, currentFingerprint)
        }
    }

    private fun fingerprintUnavailableReason(frozenFingerprint: String?, currentFingerprint: String?): String? = when {
        frozenFingerprint == null && currentFingerprint == null -> "frozen_and_current_fingerprint_unavailable"
        frozenFingerprint == null -> "frozen_fingerprint_unavailable"
        currentFingerprint == null -> "current_fingerprint_unavailable"
        else -> null
    }

    private fun previewSaveEventMetadata(
        forceMismatchOverride: Boolean,
        fingerprintUnavailableReason: String?,
    ): JsonObject = buildJsonObject {
        if (forceMismatchOverride) put("forceMismatchOverride", true)
        if (fingerprintUnavailableReason != null) {
            put("fingerprintUnavailableReason", fingerprintUnavailableReason)
        }
    }

    private fun validatePreviewPendingItems(
        screen: SnapshotDto,
        items: List<AnnotationDraftDto>,
        allowBlankComments: Boolean,
    ) {
        try {
            items.forEach { pending ->
                targetEvidenceService.validateFeedbackTarget(
                    screen = screen,
                    targetType = pending.targetType,
                    bounds = pending.bounds,
                    nodeUid = pending.nodeUid,
                    comment = pending.comment,
                    allowBlankComment = allowBlankComments,
                    missingNodeContext = "preview",
                )
            }
        } catch (error: IllegalArgumentException) {
            throw error.asPreviewFeedbackRequestValidationException()
        }
    }

    private inline fun requirePreviewFeedbackRequest(value: Boolean, lazyMessage: () -> String) {
        if (!value) {
            throw PreviewFeedbackRequestValidationException(lazyMessage())
        }
    }

    private fun IllegalArgumentException.asPreviewFeedbackRequestValidationException():
        PreviewFeedbackRequestValidationException =
        if (this is PreviewFeedbackRequestValidationException) this else {
            PreviewFeedbackRequestValidationException(message ?: "Invalid feedback item request", this)
        }

    fun clearDraftItems(sessionId: String): SessionDto = store.clearDraftItems(sessionId)

    fun sendDraftToAgent(
        sessionId: String,
        prompt: String? = null,
        targetItemIds: List<String>? = null,
    ): SessionDto = store.sendDraftToAgent(
        sessionId,
        markdownSnapshot = prompt?.takeIf { it.isNotBlank() } ?: FeedbackQueueFormatter.toMarkdown(store.getSession(sessionId)),
        targetItemIds = targetItemIds,
    )

    fun markReadyForAgent(sessionId: String): SessionDto = store.markReadyForAgent(sessionId)

    private fun fallbackPreviewRecord(sessionId: String, previewId: String, screen: SnapshotDto): PreviewRecord {
        val session = store.getSession(sessionId)
        val sanitizedScreen = screen.withExistingScreenshotArtifactsOnly()
        val sourceIndex = runBlocking { targetEvidenceService.readSourceIndexOrNull(session.packageName, sanitizedScreen) }
        return PreviewRecord(
            sessionId = sessionId,
            projectRoot = session.projectRoot,
            snapshot = io.beyondwin.fixthis.mcp.console.FeedbackPreviewSnapshot(previewId = previewId, screen = sanitizedScreen),
            sourceIndex = sourceIndex,
        )
    }

    private fun PreviewRecord.deletePreviewCacheDirectory() {
        val previewRoot = java.io.File(projectRoot, ".fixthis/preview-cache/$sessionId").canonicalFile
        val previewDirectory = java.io.File(previewRoot, snapshot.previewId).canonicalFile
        if (previewDirectory.toPath().startsWith(previewRoot.toPath())) {
            previewDirectory.deleteRecursively()
        }
    }

    private fun SnapshotDto.withExistingScreenshotArtifactsOnly(): SnapshotDto {
        val screenshot = screenshot ?: return this
        fun existingPath(path: String?): String? = path?.takeIf { it.isNotBlank() }?.takeIf { java.io.File(it).isFile }
        return copy(
            screenshot = screenshot.copy(
                desktopFullPath = existingPath(screenshot.desktopFullPath),
                desktopCropPath = existingPath(screenshot.desktopCropPath),
            ),
        )
    }
}
