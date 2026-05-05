package io.github.pointpatch.mcp.console

import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.mcp.session.CapturedScreen
import kotlinx.serialization.Serializable

@Serializable
data class FeedbackPreviewSnapshot(
    val previewId: String,
    val screen: CapturedScreen,
)

@Serializable
data class SavePreviewFeedbackItemsRequest(
    val previewId: String,
    val items: List<PendingDraftFeedbackItem>,
)

@Serializable
data class PendingDraftFeedbackItem(
    val targetType: FeedbackTargetType,
    val bounds: PointPatchRect,
    val nodeUid: String? = null,
    val comment: String,
)
