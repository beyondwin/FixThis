package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.domain.annotation.Annotation
import io.github.pointpatch.compose.core.domain.annotation.AnnotationDelivery
import io.github.pointpatch.compose.core.domain.annotation.AnnotationStatus
import io.github.pointpatch.compose.core.domain.annotation.AnnotationTarget
import io.github.pointpatch.compose.core.domain.annotation.SnapshotScreenshot
import io.github.pointpatch.compose.core.domain.common.AnnotationId
import io.github.pointpatch.compose.core.domain.common.SessionId
import io.github.pointpatch.compose.core.domain.common.SnapshotId
import io.github.pointpatch.compose.core.domain.session.Session
import io.github.pointpatch.compose.core.domain.session.SessionHandoffBatch
import io.github.pointpatch.compose.core.domain.session.SessionStatus
import io.github.pointpatch.compose.core.domain.snapshot.Snapshot
import io.github.pointpatch.compose.core.domain.snapshot.SnapshotRoot

fun FeedbackSession.toDomainSession(): Session =
    Session(
        id = SessionId(sessionId),
        packageName = packageName,
        projectRoot = projectRoot,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        snapshots = screens.map(CapturedScreen::toDomainSnapshot),
        annotations = items.map { item -> item.toDomainAnnotation(sessionId) },
        handoffBatches = handoffBatches.map { batch ->
            SessionHandoffBatch(
                id = batch.batchId,
                sequenceNumber = batch.sequenceNumber,
                createdAtEpochMillis = batch.createdAtEpochMillis,
                annotationIds = batch.itemIds,
                markdownSnapshot = batch.markdownSnapshot,
            )
        },
        status = status.toDomainStatus(),
    )

fun Session.toFeedbackSessionDto(): FeedbackSession =
    FeedbackSession(
        sessionId = id.value,
        packageName = packageName,
        projectRoot = projectRoot,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        screens = snapshots.map(Snapshot::toCapturedScreenDto),
        items = annotations.map(Annotation::toFeedbackItemDto),
        handoffBatches = handoffBatches.map { batch ->
            FeedbackHandoffBatch(
                batchId = batch.id,
                sequenceNumber = batch.sequenceNumber,
                createdAtEpochMillis = batch.createdAtEpochMillis,
                itemIds = batch.annotationIds,
                markdownSnapshot = batch.markdownSnapshot,
            )
        },
        status = status.toFeedbackSessionStatusDto(),
    )

fun CapturedScreen.toDomainSnapshot(): Snapshot =
    Snapshot(
        id = SnapshotId(screenId),
        capturedAtEpochMillis = capturedAtEpochMillis,
        activityName = activityName,
        displayName = displayName,
        screenshot = screenshot?.toDomainScreenshot(),
        roots = roots.map { root ->
            SnapshotRoot(
                rootIndex = root.rootIndex,
                boundsInWindow = root.boundsInWindow,
                mergedNodes = root.mergedNodes,
                unmergedNodes = root.unmergedNodes,
            )
        },
        sourceIndexAvailable = sourceIndexAvailable,
        errors = errors,
    )

fun Snapshot.toCapturedScreenDto(): CapturedScreen =
    CapturedScreen(
        screenId = id.value,
        capturedAtEpochMillis = capturedAtEpochMillis,
        activityName = activityName,
        displayName = displayName,
        screenshot = screenshot?.toFeedbackScreenshotDto(),
        roots = roots.map { root ->
            FeedbackScreenRoot(
                rootIndex = root.rootIndex,
                boundsInWindow = root.boundsInWindow,
                mergedNodes = root.mergedNodes,
                unmergedNodes = root.unmergedNodes,
            )
        },
        sourceIndexAvailable = sourceIndexAvailable,
        errors = errors,
    )

fun FeedbackItem.toDomainAnnotation(sessionId: String): Annotation =
    Annotation(
        id = AnnotationId(itemId),
        sessionId = SessionId(sessionId),
        snapshotId = SnapshotId(screenId),
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        target = target.toDomainTarget(),
        selectedNode = selectedNode,
        nearbyNodes = nearbyNodes,
        sourceCandidates = sourceCandidates,
        screenshotCrop = screenshotCrop?.toDomainScreenshot(),
        comment = comment,
        sequenceNumber = sequenceNumber,
        delivery = delivery.toDomainDelivery(),
        handoffBatchId = handoffBatchId,
        sentAtEpochMillis = sentAtEpochMillis,
        status = status.toDomainStatus(),
        agentSummary = agentSummary,
    )

fun Annotation.toFeedbackItemDto(): FeedbackItem =
    FeedbackItem(
        itemId = id.value,
        screenId = snapshotId.value,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        target = target.toFeedbackTargetDto(),
        selectedNode = selectedNode,
        nearbyNodes = nearbyNodes,
        sourceCandidates = sourceCandidates,
        screenshotCrop = screenshotCrop?.toFeedbackScreenshotDto(),
        comment = comment,
        sequenceNumber = sequenceNumber,
        delivery = delivery.toFeedbackDeliveryDto(),
        handoffBatchId = handoffBatchId,
        sentAtEpochMillis = sentAtEpochMillis,
        status = status.toFeedbackItemStatusDto(),
        agentSummary = agentSummary,
    )

private fun FeedbackItemStatus.toDomainStatus(): AnnotationStatus =
    when (this) {
        FeedbackItemStatus.OPEN,
        FeedbackItemStatus.READY -> AnnotationStatus.OPEN
        FeedbackItemStatus.IN_PROGRESS -> AnnotationStatus.IN_PROGRESS
        FeedbackItemStatus.RESOLVED -> AnnotationStatus.RESOLVED
        FeedbackItemStatus.NEEDS_CLARIFICATION -> AnnotationStatus.NEEDS_CLARIFICATION
        FeedbackItemStatus.WONT_FIX -> AnnotationStatus.WONT_FIX
    }

private fun AnnotationStatus.toFeedbackItemStatusDto(): FeedbackItemStatus =
    when (this) {
        AnnotationStatus.OPEN -> FeedbackItemStatus.OPEN
        AnnotationStatus.IN_PROGRESS -> FeedbackItemStatus.IN_PROGRESS
        AnnotationStatus.RESOLVED -> FeedbackItemStatus.RESOLVED
        AnnotationStatus.NEEDS_CLARIFICATION -> FeedbackItemStatus.NEEDS_CLARIFICATION
        AnnotationStatus.WONT_FIX -> FeedbackItemStatus.WONT_FIX
    }

private fun FeedbackDelivery.toDomainDelivery(): AnnotationDelivery =
    when (this) {
        FeedbackDelivery.DRAFT -> AnnotationDelivery.DRAFT
        FeedbackDelivery.SENT -> AnnotationDelivery.SENT
    }

private fun AnnotationDelivery.toFeedbackDeliveryDto(): FeedbackDelivery =
    when (this) {
        AnnotationDelivery.DRAFT -> FeedbackDelivery.DRAFT
        AnnotationDelivery.SENT -> FeedbackDelivery.SENT
    }

private fun FeedbackSessionStatus.toDomainStatus(): SessionStatus =
    when (this) {
        FeedbackSessionStatus.ACTIVE -> SessionStatus.ACTIVE
        FeedbackSessionStatus.READY_FOR_AGENT -> SessionStatus.READY_FOR_AGENT
        FeedbackSessionStatus.CLOSED -> SessionStatus.CLOSED
    }

private fun SessionStatus.toFeedbackSessionStatusDto(): FeedbackSessionStatus =
    when (this) {
        SessionStatus.ACTIVE -> FeedbackSessionStatus.ACTIVE
        SessionStatus.READY_FOR_AGENT -> FeedbackSessionStatus.READY_FOR_AGENT
        SessionStatus.CLOSED -> FeedbackSessionStatus.CLOSED
    }

private fun FeedbackTarget.toDomainTarget(): AnnotationTarget =
    when (this) {
        is FeedbackTarget.Area -> AnnotationTarget.Area(boundsInWindow)
        is FeedbackTarget.Node -> AnnotationTarget.Node(nodeUid = nodeUid, boundsInWindow = boundsInWindow)
    }

private fun AnnotationTarget.toFeedbackTargetDto(): FeedbackTarget =
    when (this) {
        is AnnotationTarget.Area -> FeedbackTarget.Area(boundsInWindow)
        is AnnotationTarget.Node -> FeedbackTarget.Node(nodeUid = nodeUid, boundsInWindow = boundsInWindow)
    }

private fun FeedbackScreenshot.toDomainScreenshot(): SnapshotScreenshot =
    SnapshotScreenshot(
        fullPath = fullPath,
        cropPath = cropPath,
        desktopFullPath = desktopFullPath,
        desktopCropPath = desktopCropPath,
        width = width,
        height = height,
        captureFailedReason = captureFailedReason,
    )

private fun SnapshotScreenshot.toFeedbackScreenshotDto(): FeedbackScreenshot =
    FeedbackScreenshot(
        fullPath = fullPath,
        cropPath = cropPath,
        desktopFullPath = desktopFullPath,
        desktopCropPath = desktopCropPath,
        width = width,
        height = height,
        captureFailedReason = captureFailedReason,
    )
