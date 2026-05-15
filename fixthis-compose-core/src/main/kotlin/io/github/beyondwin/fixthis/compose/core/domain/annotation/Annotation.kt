package io.github.beyondwin.fixthis.compose.core.domain.annotation

import io.github.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.github.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.github.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.github.beyondwin.fixthis.compose.core.domain.evidence.AnnotationEvidence
import io.github.beyondwin.fixthis.compose.core.domain.evidence.SourceHint
import io.github.beyondwin.fixthis.compose.core.domain.evidence.TargetReliabilityAssessment
import io.github.beyondwin.fixthis.compose.core.domain.ui.DomainRect
import io.github.beyondwin.fixthis.compose.core.domain.ui.SemanticsNodeSnapshot

data class Annotation(
    val id: AnnotationId,
    val sessionId: SessionId,
    val snapshotId: SnapshotId,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val target: AnnotationTarget,
    val selectedNode: SemanticsNodeSnapshot? = null,
    val nearbyNodes: List<SemanticsNodeSnapshot> = emptyList(),
    val sourceCandidates: List<SourceHint> = emptyList(),
    val screenshotCrop: SnapshotScreenshot? = null,
    val comment: String,
    val sequenceNumber: Int? = null,
    val delivery: AnnotationDelivery = AnnotationDelivery.DRAFT,
    val handoffBatchId: String? = null,
    val sentAtEpochMillis: Long? = null,
    val status: AnnotationStatus = AnnotationStatus.OPEN,
    val agentSummary: String? = null,
    val targetEvidence: AnnotationEvidence? = null,
    val targetReliability: TargetReliabilityAssessment? = null,
)

sealed interface AnnotationTarget {
    data class Node(val nodeUid: String, val boundsInWindow: DomainRect) : AnnotationTarget
    data class Area(val boundsInWindow: DomainRect) : AnnotationTarget
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
