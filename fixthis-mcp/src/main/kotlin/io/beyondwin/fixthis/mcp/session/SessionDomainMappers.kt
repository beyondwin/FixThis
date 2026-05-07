package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationDelivery
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatus
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationTarget
import io.beyondwin.fixthis.compose.core.domain.annotation.SnapshotScreenshot
import io.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.beyondwin.fixthis.compose.core.domain.session.Session
import io.beyondwin.fixthis.compose.core.domain.session.SessionHandoffBatch
import io.beyondwin.fixthis.compose.core.domain.session.SessionStatus
import io.beyondwin.fixthis.compose.core.domain.snapshot.Snapshot
import io.beyondwin.fixthis.compose.core.domain.snapshot.SnapshotRoot

fun SessionDto.toDomainSession(): Session =
    Session(
        id = SessionId(sessionId),
        packageName = packageName,
        projectRoot = projectRoot,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        snapshots = screens.map(SnapshotDto::toDomainSnapshot),
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

fun Session.toSessionDto(): SessionDto =
    SessionDto(
        sessionId = id.value,
        packageName = packageName,
        projectRoot = projectRoot,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        screens = snapshots.map(Snapshot::toSnapshotDto),
        items = annotations.map(Annotation::toAnnotationDto),
        handoffBatches = handoffBatches.map { batch ->
            FeedbackHandoffBatch(
                batchId = batch.id,
                sequenceNumber = batch.sequenceNumber,
                createdAtEpochMillis = batch.createdAtEpochMillis,
                itemIds = batch.annotationIds,
                markdownSnapshot = batch.markdownSnapshot,
            )
        },
        status = status.toSessionStatusDto(),
    )

fun SnapshotDto.toDomainSnapshot(): Snapshot =
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

fun Snapshot.toSnapshotDto(): SnapshotDto =
    SnapshotDto(
        screenId = id.value,
        capturedAtEpochMillis = capturedAtEpochMillis,
        activityName = activityName,
        displayName = displayName,
        screenshot = screenshot?.toSnapshotScreenshotDto(),
        roots = roots.map { root ->
            SnapshotRootDto(
                rootIndex = root.rootIndex,
                boundsInWindow = root.boundsInWindow,
                mergedNodes = root.mergedNodes,
                unmergedNodes = root.unmergedNodes,
            )
        },
        sourceIndexAvailable = sourceIndexAvailable,
        errors = errors,
    )

fun AnnotationDto.toDomainAnnotation(sessionId: String): Annotation =
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
        targetEvidence = targetEvidence,
    )

fun Annotation.toAnnotationDto(): AnnotationDto =
    AnnotationDto(
        itemId = id.value,
        screenId = snapshotId.value,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        target = target.toAnnotationTargetDto(),
        selectedNode = selectedNode,
        nearbyNodes = nearbyNodes,
        sourceCandidates = sourceCandidates,
        screenshotCrop = screenshotCrop?.toSnapshotScreenshotDto(),
        comment = comment,
        sequenceNumber = sequenceNumber,
        delivery = delivery.toFeedbackDeliveryDto(),
        handoffBatchId = handoffBatchId,
        sentAtEpochMillis = sentAtEpochMillis,
        status = status.toAnnotationStatusDto(),
        agentSummary = agentSummary,
        targetEvidence = targetEvidence,
    )

private fun AnnotationStatusDto.toDomainStatus(): AnnotationStatus =
    when (this) {
        AnnotationStatusDto.OPEN,
        AnnotationStatusDto.READY -> AnnotationStatus.OPEN
        AnnotationStatusDto.IN_PROGRESS -> AnnotationStatus.IN_PROGRESS
        AnnotationStatusDto.RESOLVED -> AnnotationStatus.RESOLVED
        AnnotationStatusDto.NEEDS_CLARIFICATION -> AnnotationStatus.NEEDS_CLARIFICATION
        AnnotationStatusDto.WONT_FIX -> AnnotationStatus.WONT_FIX
    }

private fun AnnotationStatus.toAnnotationStatusDto(): AnnotationStatusDto =
    when (this) {
        AnnotationStatus.OPEN -> AnnotationStatusDto.OPEN
        AnnotationStatus.IN_PROGRESS -> AnnotationStatusDto.IN_PROGRESS
        AnnotationStatus.RESOLVED -> AnnotationStatusDto.RESOLVED
        AnnotationStatus.NEEDS_CLARIFICATION -> AnnotationStatusDto.NEEDS_CLARIFICATION
        AnnotationStatus.WONT_FIX -> AnnotationStatusDto.WONT_FIX
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

private fun SessionStatusDto.toDomainStatus(): SessionStatus =
    when (this) {
        SessionStatusDto.ACTIVE -> SessionStatus.ACTIVE
        SessionStatusDto.READY_FOR_AGENT -> SessionStatus.READY_FOR_AGENT
        SessionStatusDto.CLOSED -> SessionStatus.CLOSED
    }

private fun SessionStatus.toSessionStatusDto(): SessionStatusDto =
    when (this) {
        SessionStatus.ACTIVE -> SessionStatusDto.ACTIVE
        SessionStatus.READY_FOR_AGENT -> SessionStatusDto.READY_FOR_AGENT
        SessionStatus.CLOSED -> SessionStatusDto.CLOSED
    }

private fun AnnotationTargetDto.toDomainTarget(): AnnotationTarget =
    when (this) {
        is AnnotationTargetDto.Area -> AnnotationTarget.Area(boundsInWindow)
        is AnnotationTargetDto.Node -> AnnotationTarget.Node(nodeUid = nodeUid, boundsInWindow = boundsInWindow)
    }

private fun AnnotationTarget.toAnnotationTargetDto(): AnnotationTargetDto =
    when (this) {
        is AnnotationTarget.Area -> AnnotationTargetDto.Area(boundsInWindow)
        is AnnotationTarget.Node -> AnnotationTargetDto.Node(nodeUid = nodeUid, boundsInWindow = boundsInWindow)
    }

private fun SnapshotScreenshotDto.toDomainScreenshot(): SnapshotScreenshot =
    SnapshotScreenshot(
        fullPath = fullPath,
        cropPath = cropPath,
        desktopFullPath = desktopFullPath,
        desktopCropPath = desktopCropPath,
        width = width,
        height = height,
        captureFailedReason = captureFailedReason,
    )

private fun SnapshotScreenshot.toSnapshotScreenshotDto(): SnapshotScreenshotDto =
    SnapshotScreenshotDto(
        fullPath = fullPath,
        cropPath = cropPath,
        desktopFullPath = desktopFullPath,
        desktopCropPath = desktopCropPath,
        width = width,
        height = height,
        captureFailedReason = captureFailedReason,
    )
