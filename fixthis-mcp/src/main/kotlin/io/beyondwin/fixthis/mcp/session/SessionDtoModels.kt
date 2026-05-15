package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisError
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.TargetEvidence
import io.beyondwin.fixthis.compose.core.model.TargetReliability
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
    val nextItemSequenceNumber: Int = 1,
)

internal fun SessionDto.migratedNextItemSequenceNumber(): Int {
    val nextFromItems = items.mapNotNull { it.sequenceNumber }.maxOrNull()?.plus(1) ?: 1
    return maxOf(nextItemSequenceNumber, nextFromItems)
}

internal fun SessionDto.withMigratedItemSequenceCounter(): SessionDto {
    val migrated = migratedNextItemSequenceNumber()
    return if (nextItemSequenceNumber == migrated) this else copy(nextItemSequenceNumber = migrated)
}

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
    val orientation: String? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val densityDpi: Int? = null,
    val windowMode: String? = null,
    val systemUiVisible: Boolean? = null,
    val systemUiKind: String? = null,
    val fingerprint: String? = null,
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
    val editSurfaceCandidates: List<EditSurfaceCandidateDto> = emptyList(),
    val screenshotCrop: SnapshotScreenshotDto? = null,
    val label: String? = null,
    val severity: AnnotationSeverityDto = AnnotationSeverityDto.MED,
    val comment: String,
    val sequenceNumber: Int? = null,
    val delivery: FeedbackDelivery = FeedbackDelivery.DRAFT,
    val handoffBatchId: String? = null,
    val sentAtEpochMillis: Long? = null,
    val lastHandedOffAtEpochMillis: Long? = null,
    val status: AnnotationStatusDto = AnnotationStatusDto.OPEN,
    val agentSummary: String? = null,
    val targetEvidence: TargetEvidence? = null,
    val targetReliability: TargetReliability? = null,
)

@Serializable
data class EditSurfaceCandidateDto(
    val kind: EditSurfaceKindDto,
    val file: String,
    val repoFile: String? = null,
    val line: Int? = null,
    val confidence: SelectionConfidence,
    val reasons: List<EditSurfaceReasonDto> = emptyList(),
    val note: String? = null,
)

@Serializable
enum class EditSurfaceKindDto {
    CONTAINER_COLOR,
    TEXT_COLOR,
    TYPOGRAPHY,
    SPACING,
    CHIP_COLOR,
    COMPONENT_RENDERER,
    UNKNOWN,
}

@Serializable
enum class EditSurfaceReasonDto {
    STYLE_INTENT,
    LAYOUT_INTENT,
    TYPOGRAPHY_INTENT,
    TARGET_OWNER,
    SELECTED_TEXT_RENDERER,
    COMPONENT_DEFINITION,
    CALL_SITE,
    LIST_ITEM_SPACING,
    COMPONENT_CONTAINER,
}

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
