package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.model.PointPatchError
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.SourceCandidate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionDto(
    val schemaVersion: String = "1.0",
    val sessionId: String,
    val packageName: String,
    val projectRoot: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val screens: List<SnapshotDto> = emptyList(),
    val items: List<AnnotationDto> = emptyList(),
    val handoffBatches: List<FeedbackHandoffBatch> = emptyList(),
    val status: SessionStatusDto = SessionStatusDto.ACTIVE,
)

@Serializable
enum class SessionStatusDto {
    @SerialName("active")
    ACTIVE,

    @SerialName("ready_for_agent")
    READY_FOR_AGENT,

    @SerialName("closed")
    CLOSED,
}

@Serializable
data class SnapshotDto(
    val screenId: String,
    val capturedAtEpochMillis: Long,
    val activityName: String? = null,
    val displayName: String,
    val screenshot: SnapshotScreenshotDto? = null,
    val roots: List<SnapshotRootDto> = emptyList(),
    val sourceIndexAvailable: Boolean = false,
    val errors: List<PointPatchError> = emptyList(),
)

@Serializable
data class SnapshotRootDto(
    val rootIndex: Int,
    val boundsInWindow: PointPatchRect,
    val mergedNodes: List<PointPatchNode> = emptyList(),
    val unmergedNodes: List<PointPatchNode> = emptyList(),
)

@Serializable
data class SnapshotScreenshotDto(
    val fullPath: String? = null,
    val cropPath: String? = null,
    val desktopFullPath: String? = null,
    val desktopCropPath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val captureFailedReason: String? = null,
)

@Serializable
data class AnnotationDto(
    val itemId: String,
    val screenId: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val target: AnnotationTargetDto,
    val selectedNode: PointPatchNode? = null,
    val nearbyNodes: List<PointPatchNode> = emptyList(),
    val sourceCandidates: List<SourceCandidate> = emptyList(),
    val screenshotCrop: SnapshotScreenshotDto? = null,
    val comment: String,
    val sequenceNumber: Int? = null,
    val delivery: FeedbackDelivery = FeedbackDelivery.DRAFT,
    val handoffBatchId: String? = null,
    val sentAtEpochMillis: Long? = null,
    val status: AnnotationStatusDto = AnnotationStatusDto.OPEN,
    val agentSummary: String? = null,
)

@Serializable
sealed interface AnnotationTargetDto {
    @Serializable
    @SerialName("semantics_node")
    data class Node(val nodeUid: String, val boundsInWindow: PointPatchRect) : AnnotationTargetDto

    @Serializable
    @SerialName("visual_area")
    data class Area(val boundsInWindow: PointPatchRect) : AnnotationTargetDto
}

@Serializable
enum class AnnotationStatusDto {
    @SerialName("open")
    OPEN,

    @SerialName("ready")
    READY,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("resolved")
    RESOLVED,

    @SerialName("needs_clarification")
    NEEDS_CLARIFICATION,

    @SerialName("wont_fix")
    WONT_FIX,
}
