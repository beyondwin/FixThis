package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.session.AnnotationSeverityDto
import io.beyondwin.fixthis.mcp.session.AnnotationStatusDto
import io.beyondwin.fixthis.mcp.session.SessionDto
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import kotlinx.serialization.Serializable

@Serializable
data class FeedbackPreviewSnapshot(
    val previewId: String,
    val screen: SnapshotDto,
)

@Serializable
data class SaveSnapshotRequest(
    val sessionId: String? = null,
    val previewId: String,
    val workspaceId: String? = null,
    val items: List<AnnotationDraftDto>,
    val screen: SnapshotDto? = null,
    val frozenFingerprint: String? = null,
    val forceMismatchOverride: Boolean = false,
)

@Serializable
data class AgentHandoffRequest(
    val sessionId: String? = null,
    val itemIds: List<String> = emptyList(),
)

@Serializable
data class AgentHandoffResponse(
    val session: SessionDto,
    val prompt: String,
)

@Serializable
data class AnnotationDraftDto(
    val draftItemId: String? = null,
    val targetType: FeedbackTargetType,
    val bounds: FixThisRect,
    val nodeUid: String? = null,
    val label: String? = null,
    val severity: AnnotationSeverityDto = AnnotationSeverityDto.MED,
    val status: AnnotationStatusDto = AnnotationStatusDto.OPEN,
    val comment: String,
)
