package io.github.pointpatch.mcp.console

import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.mcp.session.SnapshotDto
import kotlinx.serialization.Serializable

@Serializable
data class FeedbackPreviewSnapshot(
    val previewId: String,
    val screen: SnapshotDto,
)

@Serializable
data class SaveSnapshotRequest(
    val previewId: String,
    val items: List<AnnotationDraftDto>,
)

@Serializable
data class AgentHandoffRequest(
    val prompt: String? = null,
)

@Serializable
data class AnnotationDraftDto(
    val targetType: FeedbackTargetType,
    val bounds: PointPatchRect,
    val nodeUid: String? = null,
    val comment: String,
)
