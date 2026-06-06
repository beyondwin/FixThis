package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto

object SessionReducer {
    fun reduce(session: SessionDto, mutation: SessionMutation): SessionDto = when (mutation) {
        is SessionMutation.AddScreen -> session.copy(
            screens = session.screens + mutation.screen,
            updatedAtEpochMillis = mutation.now,
        )
        is SessionMutation.AddScreenWithItems -> session.copy(
            screens = session.screens + mutation.screen,
            items = session.items + mutation.items,
            updatedAtEpochMillis = mutation.now,
        )
        is SessionMutation.ReplaceItems -> session.copy(
            items = mutation.items,
            updatedAtEpochMillis = mutation.now,
        )
        is SessionMutation.DeleteScreen -> deleteScreen(session, mutation.screenId, mutation.now)
        is SessionMutation.DeleteItem -> deleteItem(session, mutation.itemId, mutation.now)
        is SessionMutation.AddHandoff -> session.copy(
            items = mutation.items,
            handoffBatches = session.handoffBatches + mutation.batch,
            status = SessionStatusDto.READY_FOR_AGENT,
            updatedAtEpochMillis = mutation.now,
        )
        is SessionMutation.Close -> session.copy(
            status = SessionStatusDto.CLOSED,
            updatedAtEpochMillis = mutation.now,
        )
        is SessionMutation.MarkReadyForAgent -> session.copy(
            status = SessionStatusDto.READY_FOR_AGENT,
            updatedAtEpochMillis = mutation.now,
        )
    }

    private fun deleteScreen(session: SessionDto, screenId: String, now: Long): SessionDto {
        val removedItemIds = session.items
            .filter { it.screenId == screenId }
            .map { it.itemId }
            .toSet()
        return session.copy(
            screens = session.screens.filterNot { it.screenId == screenId },
            items = session.items.filterNot { it.screenId == screenId },
            handoffBatches = pruneHandoffBatches(session.handoffBatches, removedItemIds),
            updatedAtEpochMillis = now,
        )
    }

    private fun deleteItem(session: SessionDto, itemId: String, now: Long): SessionDto = session.copy(
        items = session.items.filterNot { it.itemId == itemId },
        handoffBatches = pruneHandoffBatches(session.handoffBatches, setOf(itemId)),
        updatedAtEpochMillis = now,
    )

    private fun pruneHandoffBatches(
        batches: List<FeedbackHandoffBatch>,
        removedItemIds: Set<String>,
    ): List<FeedbackHandoffBatch> = batches
        .map { batch -> batch.copy(itemIds = batch.itemIds.filterNot { it in removedItemIds }) }
        .filter { it.itemIds.isNotEmpty() }
}
