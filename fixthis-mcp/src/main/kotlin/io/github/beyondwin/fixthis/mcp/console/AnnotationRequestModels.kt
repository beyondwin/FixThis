package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.AnnotationSeverityDto
import io.github.beyondwin.fixthis.mcp.session.AnnotationStatusDto
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
    val sessionId: String? = null,
    val screenId: String,
    val comment: String,
    val targetType: FeedbackTargetType = FeedbackTargetType.AREA,
    val bounds: FixThisRect,
    val nodeUid: String? = null,
)

@Serializable
data class UpdateAnnotationRequest(
    val sessionId: String? = null,
    val label: String? = null,
    val severity: AnnotationSeverityDto? = null,
    val comment: String? = null,
    val status: AnnotationStatusDto? = null,
)

@Serializable
data class HandoffPreviewRequest(
    val itemIds: List<String> = emptyList(),
)

@Serializable
data class MarkHandedOffRequest(
    val itemIds: List<String> = emptyList(),
)
