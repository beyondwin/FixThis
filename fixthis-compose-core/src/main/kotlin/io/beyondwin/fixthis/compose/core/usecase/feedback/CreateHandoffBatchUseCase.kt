package io.beyondwin.fixthis.compose.core.usecase.feedback

import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationDelivery
import io.beyondwin.fixthis.compose.core.domain.session.Session
import io.beyondwin.fixthis.compose.core.domain.session.SessionHandoffBatch
import io.beyondwin.fixthis.compose.core.domain.session.SessionRepository
import io.beyondwin.fixthis.compose.core.domain.session.SessionStatus

class CreateHandoffBatchUseCase(
    private val sessions: SessionRepository,
    private val clock: () -> Long,
    private val idGenerator: () -> String,
) {
    suspend operator fun invoke(command: CreateHandoffBatchCommand): Session {
        require(command.annotationIds.isNotEmpty()) { "annotationIds must not be empty" }
        val session = sessions.find(command.sessionId)
            ?: throw IllegalArgumentException("Unknown session: ${command.sessionId.value}")
        val targetIds = command.annotationIds.map { it.value }.toSet()
        val annotations = session.annotations.filter {
            it.id.value in targetIds && it.delivery == AnnotationDelivery.DRAFT
        }
        require(annotations.isNotEmpty()) { "No matching draft annotations to hand off" }
        val now = clock()
        val batchId = idGenerator()
        val batch = SessionHandoffBatch(
            id = batchId,
            sequenceNumber = session.handoffBatches.size + 1,
            createdAtEpochMillis = now,
            annotationIds = annotations.map { it.id.value },
            markdownSnapshot = command.markdownSnapshot,
        )
        val updated = session.copy(
            annotations = session.annotations.map { annotation ->
                if (annotation.id.value in targetIds && annotation.delivery == AnnotationDelivery.DRAFT) {
                    annotation.copy(
                        delivery = AnnotationDelivery.SENT,
                        handoffBatchId = batchId,
                        sentAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    )
                } else {
                    annotation
                }
            },
            handoffBatches = session.handoffBatches + batch,
            status = SessionStatus.READY_FOR_AGENT,
            updatedAtEpochMillis = now,
        )
        return sessions.save(updated)
    }
}
