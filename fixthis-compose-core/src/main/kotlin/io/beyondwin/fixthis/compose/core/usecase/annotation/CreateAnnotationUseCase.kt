package io.beyondwin.fixthis.compose.core.usecase.annotation

import io.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationRepository
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatus
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationTarget
import io.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.beyondwin.fixthis.compose.core.domain.session.SessionRepository

class CreateAnnotationUseCase(
    private val sessions: SessionRepository,
    private val annotations: AnnotationRepository,
    private val clock: () -> Long,
    private val idGenerator: () -> AnnotationId,
) {
    suspend operator fun invoke(
        sessionId: SessionId,
        snapshotId: SnapshotId,
        target: AnnotationTarget,
        comment: String,
    ): Annotation {
        require(comment.isNotBlank()) { "Annotation comment must not be blank" }
        val session = sessions.find(sessionId)
            ?: throw IllegalArgumentException("Unknown session: ${sessionId.value}")
        val now = clock()
        val annotation = Annotation(
            id = idGenerator(),
            sessionId = session.id,
            snapshotId = snapshotId,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            target = target,
            comment = comment,
            status = AnnotationStatus.OPEN,
        )
        return annotations.save(annotation)
    }
}
