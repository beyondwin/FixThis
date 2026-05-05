package io.github.pointpatch.mcp.console

import io.github.pointpatch.compose.core.model.PointPatchRect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FeedbackTargetType {
    @SerialName("area")
    AREA,

    @SerialName("node")
    NODE,
}

@Serializable
data class AddFeedbackItemRequest(
    val screenId: String,
    val comment: String,
    val targetType: FeedbackTargetType = FeedbackTargetType.AREA,
    val bounds: PointPatchRect,
    val nodeUid: String? = null,
)
