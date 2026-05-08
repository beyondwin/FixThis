package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.session.AnnotationSeverityDto
import io.beyondwin.fixthis.mcp.session.AnnotationStatusDto
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
    val screen: SnapshotDto? = null,
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
    val label: String? = null,
    val severity: AnnotationSeverityDto = AnnotationSeverityDto.MED,
    val status: AnnotationStatusDto = AnnotationStatusDto.OPEN,
    val comment: String,
)
