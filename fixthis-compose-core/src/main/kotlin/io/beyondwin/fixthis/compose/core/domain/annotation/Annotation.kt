package io.beyondwin.fixthis.compose.core.domain.annotation

import io.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.TargetEvidence
import io.beyondwin.fixthis.compose.core.model.TargetReliability

data class Annotation(
    val id: AnnotationId,
    val sessionId: SessionId,
    val snapshotId: SnapshotId,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val target: AnnotationTarget,
    val selectedNode: FixThisNode? = null,
    val nearbyNodes: List<FixThisNode> = emptyList(),
    val sourceCandidates: List<SourceCandidate> = emptyList(),
    val screenshotCrop: SnapshotScreenshot? = null,
    val comment: String,
    val sequenceNumber: Int? = null,
    val delivery: AnnotationDelivery = AnnotationDelivery.DRAFT,
    val handoffBatchId: String? = null,
    val sentAtEpochMillis: Long? = null,
    val status: AnnotationStatus = AnnotationStatus.OPEN,
    val agentSummary: String? = null,
    val targetEvidence: TargetEvidence? = null,
    val targetReliability: TargetReliability? = null,
)

sealed interface AnnotationTarget {
    data class Node(val nodeUid: String, val boundsInWindow: FixThisRect) : AnnotationTarget
    data class Area(val boundsInWindow: FixThisRect) : AnnotationTarget
}

enum class AnnotationDelivery {
    DRAFT,
    SENT,
}

enum class AnnotationStatus(val group: Group) {
    OPEN(Group.OPEN),
    IN_PROGRESS(Group.IN_PROGRESS),
    RESOLVED(Group.RESOLVED),
    WONT_FIX(Group.RESOLVED),
    NEEDS_CLARIFICATION(Group.OPEN),
    ;

    enum class Group {
        OPEN,
        IN_PROGRESS,
        RESOLVED,
    }
}

data class SnapshotScreenshot(
    val fullPath: String? = null,
    val cropPath: String? = null,
    val desktopFullPath: String? = null,
    val desktopCropPath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val captureFailedReason: String? = null,
)
