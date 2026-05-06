package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.session.SnapshotDto
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
    val bounds: FixThisRect,
    val nodeUid: String? = null,
    val comment: String,
)
