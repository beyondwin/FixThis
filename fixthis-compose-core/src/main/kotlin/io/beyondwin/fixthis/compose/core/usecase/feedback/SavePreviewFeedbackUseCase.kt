package io.beyondwin.fixthis.compose.core.usecase.feedback

import io.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.beyondwin.fixthis.compose.core.domain.session.Session
import io.beyondwin.fixthis.compose.core.domain.session.SessionRepository

class SavePreviewFeedbackUseCase(
    private val sessions: SessionRepository,
    private val clock: () -> Long,
) {
    suspend operator fun invoke(command: SavePreviewFeedbackCommand): Session {
        require(command.drafts.isNotEmpty()) { "drafts must not be empty" }
        if (!command.allowBlankComments) {
            require(command.drafts.none { it.comment.isBlank() }) { "Feedback comment must not be blank" }
        }
        val session = sessions.find(command.sessionId)
            ?: throw IllegalArgumentException("Unknown session: ${command.sessionId.value}")
        val now = clock()
        var nextSequence = session.nextAnnotationSequenceNumber()
        val drafts = command.drafts.map { draft ->
            require(draft.snapshotId == command.snapshot.id) {
                "Draft ${draft.id.value} points to unknown snapshot: ${draft.snapshotId.value}"
            }
            val assigned = draft.sequenceNumber ?: nextSequence++
            draft.copy(
                sessionId = command.sessionId,
                snapshotId = command.snapshot.id,
                sequenceNumber = assigned,
                updatedAtEpochMillis = now,
            )
        }
        val updated = session.copy(
            snapshots = session.snapshots.filterNot { it.id == command.snapshot.id } + command.snapshot,
            annotations = session.annotations + drafts,
            updatedAtEpochMillis = now,
        )
        return sessions.save(updated)
    }
}

internal fun Session.nextAnnotationSequenceNumber(): Int =
    (annotations.mapNotNull(Annotation::sequenceNumber).maxOrNull() ?: 0) + 1
