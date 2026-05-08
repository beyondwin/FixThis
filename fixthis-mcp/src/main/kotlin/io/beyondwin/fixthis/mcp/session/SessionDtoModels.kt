package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisError
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.TargetEvidence
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
    val errors: List<FixThisError> = emptyList(),
)

@Serializable
data class SnapshotRootDto(
    val rootIndex: Int,
    val boundsInWindow: FixThisRect,
    val mergedNodes: List<FixThisNode> = emptyList(),
    val unmergedNodes: List<FixThisNode> = emptyList(),
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
    val selectedNode: FixThisNode? = null,
    val nearbyNodes: List<FixThisNode> = emptyList(),
    val sourceCandidates: List<SourceCandidate> = emptyList(),
    val screenshotCrop: SnapshotScreenshotDto? = null,
    val label: String? = null,
    val severity: AnnotationSeverityDto = AnnotationSeverityDto.MED,
    val comment: String,
    val sequenceNumber: Int? = null,
    val delivery: FeedbackDelivery = FeedbackDelivery.DRAFT,
    val handoffBatchId: String? = null,
    val sentAtEpochMillis: Long? = null,
    val status: AnnotationStatusDto = AnnotationStatusDto.OPEN,
    val agentSummary: String? = null,
    val targetEvidence: TargetEvidence? = null,
)

@Serializable
enum class AnnotationSeverityDto {
    @SerialName("high")
    HIGH,

    @SerialName("med")
    MED,

    @SerialName("low")
    LOW,
}

@Serializable
sealed interface AnnotationTargetDto {
    @Serializable
    @SerialName("semantics_node")
    data class Node(val nodeUid: String, val boundsInWindow: FixThisRect) : AnnotationTargetDto

    @Serializable
    @SerialName("visual_area")
    data class Area(val boundsInWindow: FixThisRect) : AnnotationTargetDto
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
