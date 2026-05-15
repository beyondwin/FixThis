package io.github.beyondwin.fixthis.compose.core.usecase.feedback

import io.github.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatus
import io.github.beyondwin.fixthis.compose.core.domain.session.Session
import io.github.beyondwin.fixthis.compose.core.domain.session.SessionRepository

class ClaimAnnotationUseCase(
    private val sessions: SessionRepository,
    private val clock: () -> Long,
) {
    suspend operator fun invoke(command: ClaimAnnotationCommand): Session {
        val session = sessions.find(command.sessionId)
            ?: throw IllegalArgumentException("Unknown session: ${command.sessionId.value}")
        val now = clock()
        var found = false
        val updated = session.copy(
            annotations = session.annotations.map { annotation ->
                if (annotation.id != command.annotationId) return@map annotation
                found = true
                require(annotation.status !in ResolvedStatuses) {
                    "Cannot claim resolved annotation: ${command.annotationId.value}"
                }
                annotation.copy(
                    status = AnnotationStatus.IN_PROGRESS,
                    agentSummary = command.agentNote ?: annotation.agentSummary,
                    updatedAtEpochMillis = now,
                )
            },
            updatedAtEpochMillis = now,
        )
        require(found) { "Unknown annotation: ${command.annotationId.value}" }
        return sessions.save(updated)
    }
}

private val ResolvedStatuses = setOf(
    AnnotationStatus.RESOLVED,
    AnnotationStatus.WONT_FIX,
)
