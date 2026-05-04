package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.model.PointPatchError
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.SourceCandidate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FeedbackSession(
    val schemaVersion: String = "1.0",
    val sessionId: String,
    val packageName: String,
    val projectRoot: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val screens: List<CapturedScreen> = emptyList(),
    val items: List<FeedbackItem> = emptyList(),
    val status: FeedbackSessionStatus = FeedbackSessionStatus.ACTIVE,
)

@Serializable
enum class FeedbackSessionStatus {
    @SerialName("active")
    ACTIVE,

    @SerialName("ready_for_agent")
    READY_FOR_AGENT,

    @SerialName("closed")
    CLOSED,
}

@Serializable
data class CapturedScreen(
    val screenId: String,
    val capturedAtEpochMillis: Long,
    val activityName: String? = null,
    val displayName: String,
    val screenshot: FeedbackScreenshot? = null,
    val roots: List<FeedbackScreenRoot> = emptyList(),
    val sourceIndexAvailable: Boolean = false,
    val errors: List<PointPatchError> = emptyList(),
)

@Serializable
data class FeedbackScreenRoot(
    val rootIndex: Int,
    val boundsInWindow: PointPatchRect,
    val mergedNodes: List<PointPatchNode> = emptyList(),
    val unmergedNodes: List<PointPatchNode> = emptyList(),
)

@Serializable
data class FeedbackScreenshot(
    val fullPath: String? = null,
    val cropPath: String? = null,
    val desktopFullPath: String? = null,
    val desktopCropPath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val captureFailedReason: String? = null,
)

@Serializable
data class FeedbackItem(
    val itemId: String,
    val screenId: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val target: FeedbackTarget,
    val selectedNode: PointPatchNode? = null,
    val nearbyNodes: List<PointPatchNode> = emptyList(),
    val sourceCandidates: List<SourceCandidate> = emptyList(),
    val screenshotCrop: FeedbackScreenshot? = null,
    val comment: String,
    val status: FeedbackItemStatus = FeedbackItemStatus.OPEN,
    val agentSummary: String? = null,
)

@Serializable
sealed interface FeedbackTarget {
    @Serializable
    @SerialName("semantics_node")
    data class Node(val nodeUid: String, val boundsInWindow: PointPatchRect) : FeedbackTarget

    @Serializable
    @SerialName("visual_area")
    data class Area(val boundsInWindow: PointPatchRect) : FeedbackTarget
}

@Serializable
enum class FeedbackItemStatus {
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
