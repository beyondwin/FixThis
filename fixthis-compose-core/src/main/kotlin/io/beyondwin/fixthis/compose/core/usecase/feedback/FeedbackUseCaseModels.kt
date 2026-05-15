package io.beyondwin.fixthis.compose.core.usecase.feedback

import io.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatus
import io.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.beyondwin.fixthis.compose.core.domain.snapshot.Snapshot

data class SaveCapturedSnapshotCommand(
    val sessionId: SessionId,
    val snapshot: Snapshot,
)

data class CreateFeedbackAnnotationCommand(
    val sessionId: SessionId,
    val snapshotId: SnapshotId,
    val draft: Annotation,
)

data class SavePreviewFeedbackCommand(
    val sessionId: SessionId,
    val snapshot: Snapshot,
    val drafts: List<Annotation>,
    val allowBlankComments: Boolean = false,
)

data class CreateHandoffBatchCommand(
    val sessionId: SessionId,
    val annotationIds: List<AnnotationId>,
    val markdownSnapshot: String?,
)

data class ResolveAnnotationCommand(
    val sessionId: SessionId,
    val annotationId: AnnotationId,
    val status: AnnotationStatus,
    val summary: String?,
)

data class ClaimAnnotationCommand(
    val sessionId: SessionId,
    val annotationId: AnnotationId,
    val agentNote: String?,
)
