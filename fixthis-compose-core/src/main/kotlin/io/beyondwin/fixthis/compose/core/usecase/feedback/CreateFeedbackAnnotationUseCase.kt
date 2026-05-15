package io.beyondwin.fixthis.compose.core.usecase.feedback

import io.beyondwin.fixthis.compose.core.domain.session.Session
import io.beyondwin.fixthis.compose.core.domain.session.SessionRepository

class CreateFeedbackAnnotationUseCase(
    private val sessions: SessionRepository,
    private val clock: () -> Long,
) {
    suspend operator fun invoke(command: CreateFeedbackAnnotationCommand): Session {
        require(command.draft.comment.isNotBlank()) { "Feedback comment must not be blank" }
        val session = sessions.find(command.sessionId)
            ?: throw IllegalArgumentException("Unknown session: ${command.sessionId.value}")
        require(session.snapshots.any { it.id == command.snapshotId }) {
            "Unknown snapshot: ${command.snapshotId.value}"
        }
        val sequence = command.draft.sequenceNumber ?: session.nextAnnotationSequenceNumber()
        val now = clock()
        val created = command.draft.copy(
            sessionId = command.sessionId,
            snapshotId = command.snapshotId,
            sequenceNumber = sequence,
            updatedAtEpochMillis = now,
        )
        return sessions.save(
            session.copy(
                annotations = session.annotations + created,
                updatedAtEpochMillis = now,
            ),
        )
    }
}
