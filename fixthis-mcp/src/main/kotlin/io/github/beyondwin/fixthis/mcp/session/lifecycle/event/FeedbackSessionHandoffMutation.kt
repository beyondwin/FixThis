package io.github.beyondwin.fixthis.mcp.session.lifecycle.event

import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackDelivery
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackHandoffBatch

internal data class PreparedHandoffMutation(
    val session: SessionDto,
    val batch: FeedbackHandoffBatch,
)

internal object FeedbackSessionHandoffMutation {
    fun prepare(
        session: SessionDto,
        targetItemIds: List<String>?,
        markdownSnapshot: String?,
        now: Long,
        batchId: () -> String,
    ): PreparedHandoffMutation? {
        val targetSet = targetItemIds?.toSet()
        val candidates = session.items.filter { item ->
            val matchesTarget = targetSet == null || item.itemId in targetSet
            matchesTarget &&
                (
                    item.delivery == FeedbackDelivery.DRAFT ||
                        (item.delivery == FeedbackDelivery.SENT && item.status == AnnotationStatusDto.READY)
                    )
        }
        if (candidates.isEmpty()) return null

        val batch = FeedbackHandoffBatch(
            batchId = batchId(),
            sequenceNumber = session.handoffBatches.size + 1,
            createdAtEpochMillis = now,
            itemIds = candidates.map { it.itemId },
            markdownSnapshot = markdownSnapshot,
        )
        val updatedItems = session.items.map { item ->
            val matchesTarget = targetSet == null || item.itemId in targetSet
            when {
                item.delivery == FeedbackDelivery.DRAFT && matchesTarget -> item.copy(
                    delivery = FeedbackDelivery.SENT,
                    handoffBatchId = batch.batchId,
                    sentAtEpochMillis = now,
                    lastHandedOffAtEpochMillis = now,
                    status = AnnotationStatusDto.READY,
                    updatedAtEpochMillis = now,
                )
                item.delivery == FeedbackDelivery.SENT &&
                    item.status == AnnotationStatusDto.READY &&
                    matchesTarget -> item.copy(
                    handoffBatchId = batch.batchId,
                    lastHandedOffAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
                else -> item
            }
        }
        return PreparedHandoffMutation(
            session = session.copy(
                items = updatedItems,
                handoffBatches = session.handoffBatches + batch,
                status = SessionStatusDto.READY_FOR_AGENT,
                updatedAtEpochMillis = now,
            ),
            batch = batch,
        )
    }
}
