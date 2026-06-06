package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.migratedNextItemSequenceNumber

internal class SessionMutationService(
    private val clock: () -> Long,
    private val idGenerator: () -> String,
) {
    fun addScreen(session: SessionDto, screen: SnapshotDto): Pair<SessionDto, SnapshotDto> {
        val now = clock()
        val captured = screen.copy(
            screenId = if (screen.screenId == "pending") idGenerator() else screen.screenId,
            capturedAtEpochMillis = now,
        )
        return session.copy(
            screens = session.screens + captured,
            updatedAtEpochMillis = now,
        ) to captured
    }

    fun addScreenWithItems(session: SessionDto, screen: SnapshotDto, items: List<AnnotationDto>): SessionDto {
        require(items.isNotEmpty()) { "At least one feedback item is required" }
        val now = clock()
        val captured = screen.copy(
            screenId = if (screen.screenId == "pending") idGenerator() else screen.screenId,
            capturedAtEpochMillis = now,
        )
        val firstSequence = session.migratedNextItemSequenceNumber()
        val createdItems = items.mapIndexed { index, item ->
            item.copy(
                itemId = if (item.itemId == "pending") idGenerator() else item.itemId,
                screenId = captured.screenId,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                sequenceNumber = item.sequenceNumber ?: firstSequence + index,
                delivery = FeedbackDelivery.DRAFT,
            )
        }
        val nextSequence = createdItems.mapNotNull { it.sequenceNumber }.maxOrNull()?.plus(1) ?: firstSequence
        return session.copy(
            screens = session.screens + captured,
            items = session.items + createdItems,
            nextItemSequenceNumber = maxOf(firstSequence, nextSequence),
            updatedAtEpochMillis = now,
        )
    }

    fun deleteScreen(session: SessionDto, screenId: String): SessionDto {
        if (session.screens.none { it.screenId == screenId }) {
            throw FeedbackSessionException("SCREEN_NOT_FOUND: Unknown screen: $screenId")
        }
        val removedItemIds = session.items
            .filter { it.screenId == screenId }
            .map { it.itemId }
            .toSet()
        val updatedBatches = session.handoffBatches
            .map { batch -> batch.copy(itemIds = batch.itemIds.filterNot { it in removedItemIds }) }
            .filter { it.itemIds.isNotEmpty() }
        return session.copy(
            screens = session.screens.filterNot { it.screenId == screenId },
            items = session.items.filterNot { it.screenId == screenId },
            handoffBatches = updatedBatches,
            updatedAtEpochMillis = clock(),
        )
    }

    fun addItem(session: SessionDto, item: AnnotationDto): Pair<SessionDto, AnnotationDto> {
        require(session.screens.any { it.screenId == item.screenId }) {
            "Cannot add feedback for unknown screen: ${item.screenId}"
        }
        val now = clock()
        val sequence = session.migratedNextItemSequenceNumber()
        val createdSequence = item.sequenceNumber ?: sequence
        val created = item.copy(
            itemId = idGenerator(),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            sequenceNumber = createdSequence,
            delivery = item.delivery,
        )
        return session.copy(
            items = session.items + created,
            nextItemSequenceNumber = maxOf(sequence + 1, createdSequence + 1),
            updatedAtEpochMillis = now,
        ) to created
    }

    fun updateItemStatus(
        session: SessionDto,
        itemId: String,
        status: AnnotationStatusDto,
        summary: String?,
    ): Pair<SessionDto, AnnotationDto> {
        val allowedStatuses = setOf(
            AnnotationStatusDto.RESOLVED,
            AnnotationStatusDto.NEEDS_CLARIFICATION,
            AnnotationStatusDto.WONT_FIX,
        )
        require(status in allowedStatuses) {
            "Agent resolution status is not allowed: $status"
        }
        val now = clock()
        var updatedItem: AnnotationDto? = null
        val updatedItems = session.items.map { item ->
            if (item.itemId == itemId) {
                item.copy(
                    status = status,
                    agentSummary = summary,
                    updatedAtEpochMillis = now,
                ).also { updatedItem = it }
            } else {
                item
            }
        }
        val item = updatedItem ?: throw FeedbackSessionException("Unknown feedback item: $itemId")
        return session.copy(items = updatedItems, updatedAtEpochMillis = now) to item
    }
}
