package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.compose.core.model.FixThisRect
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
data class AddAnnotationRequest(
    val screenId: String,
    val comment: String,
    val targetType: FeedbackTargetType = FeedbackTargetType.AREA,
    val bounds: FixThisRect,
    val nodeUid: String? = null,
)
