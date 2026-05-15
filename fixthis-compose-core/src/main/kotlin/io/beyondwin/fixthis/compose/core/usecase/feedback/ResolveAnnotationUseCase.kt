package io.beyondwin.fixthis.compose.core.usecase.feedback

import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatus
import io.beyondwin.fixthis.compose.core.domain.session.Session
import io.beyondwin.fixthis.compose.core.domain.session.SessionRepository

class ResolveAnnotationUseCase(
    private val sessions: SessionRepository,
    private val clock: () -> Long,
) {
    suspend operator fun invoke(command: ResolveAnnotationCommand): Session {
        require(command.status in AllowedResolutionStatuses) {
            "Agent resolution status is not allowed: ${command.status}"
        }
        val session = sessions.find(command.sessionId)
            ?: throw IllegalArgumentException("Unknown session: ${command.sessionId.value}")
        val now = clock()
        var found = false
        val updated = session.copy(
            annotations = session.annotations.map { annotation ->
                if (annotation.id == command.annotationId) {
                    found = true
                    annotation.copy(
                        status = command.status,
                        agentSummary = command.summary,
                        updatedAtEpochMillis = now,
                    )
                } else {
                    annotation
                }
            },
            updatedAtEpochMillis = now,
        )
        require(found) { "Unknown annotation: ${command.annotationId.value}" }
        return sessions.save(updated)
    }
}

private val AllowedResolutionStatuses = setOf(
    AnnotationStatus.RESOLVED,
    AnnotationStatus.WONT_FIX,
    AnnotationStatus.NEEDS_CLARIFICATION,
)
