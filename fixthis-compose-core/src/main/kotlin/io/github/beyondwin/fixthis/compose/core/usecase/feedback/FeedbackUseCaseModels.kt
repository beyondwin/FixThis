package io.github.beyondwin.fixthis.compose.core.usecase.feedback

import io.github.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.github.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.github.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.github.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.github.beyondwin.fixthis.compose.core.domain.snapshot.Snapshot

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
