package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.console.AnnotationDraftDto
import io.beyondwin.fixthis.mcp.console.FeedbackTargetType
import kotlinx.coroutines.runBlocking

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
    ): SessionDto {
        require(items.isNotEmpty()) { "At least one feedback item is required" }
        if (!allowBlankComments) {
            require(items.none { it.comment.isBlank() }) { "Feedback comment must not be blank" }
        }
        val inFlightKey = "$sessionId:$previewId"
        val cachedPreview = synchronized(lock) {
            val record = previewCache.get(sessionId, previewId)
            if (record == null && fallbackScreen == null) {
                throw FeedbackSessionException("PREVIEW_NOT_FOUND: Unknown preview: $previewId")
            }
            if (!previewSavesInFlight.add(inFlightKey)) {
                throw FeedbackSessionException("PREVIEW_SAVE_IN_PROGRESS: Preview is already being saved: $previewId")
            }
            record
        }

        return try {
            val preview = cachedPreview ?: run {
                val fallback = checkNotNull(fallbackScreen) {
                    "PREVIEW_NOT_FOUND guard above must have rejected a null fallbackScreen"
                }
                validatePreviewPendingItems(fallback, items, allowBlankComments)
                fallbackPreviewRecord(sessionId, previewId, fallback)
            }
            val feedbackItems = items.map { pending ->
                targetEvidenceService.buildFeedbackItem(
                    screen = preview.snapshot.screen,
                    sourceIndex = preview.sourceIndex,
                    targetType = pending.targetType,
                    bounds = pending.bounds,
                    nodeUid = pending.nodeUid,
                    comment = pending.comment,
                    allowBlankComment = allowBlankComments,
                    writtenStatus = pending.status,
                    missingNodeContext = "preview",
                ).copy(
                    label = pending.label?.takeIf { it.isNotBlank() },
                    severity = pending.severity,
                )
            }
            val persistedScreen = screenshotArtifactPromoter.promote(
                projectRoot = preview.projectRoot,
                sessionId = sessionId,
                screen = preview.snapshot.screen,
            )
            val updated = store.addScreenWithItems(sessionId, persistedScreen, feedbackItems)
            val removedPreview = synchronized(lock) {
                previewSavesInFlight.remove(inFlightKey)
                previewCache.remove(sessionId, previewId)
            }
            removedPreview?.deletePreviewCacheDirectory()
            updated
        } catch (error: Throwable) {
            synchronized(lock) {
                previewSavesInFlight.remove(inFlightKey)
            }
            throw error
        }
    }

    private fun validatePreviewPendingItems(
        screen: SnapshotDto,
        items: List<AnnotationDraftDto>,
        allowBlankComments: Boolean,
    ) {
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
    }

    fun clearDraftItems(sessionId: String): SessionDto =
        store.clearDraftItems(sessionId)

    fun sendDraftToAgent(
        sessionId: String,
        prompt: String? = null,
        targetItemIds: List<String>? = null,
    ): SessionDto =
        store.sendDraftToAgent(
            sessionId,
            markdownSnapshot = prompt?.takeIf { it.isNotBlank() } ?: FeedbackQueueFormatter.toMarkdown(store.getSession(sessionId)),
            targetItemIds = targetItemIds,
        )

    fun markReadyForAgent(sessionId: String): SessionDto =
        store.markReadyForAgent(sessionId)

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
        fun existingPath(path: String?): String? =
            path?.takeIf { it.isNotBlank() }?.takeIf { java.io.File(it).isFile }
        return copy(
            screenshot = screenshot.copy(
                desktopFullPath = existingPath(screenshot.desktopFullPath),
                desktopCropPath = existingPath(screenshot.desktopCropPath),
            ),
        )
    }
}
