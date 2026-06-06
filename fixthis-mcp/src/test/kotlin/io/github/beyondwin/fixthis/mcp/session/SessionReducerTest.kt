package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionReducerTest {
    @Test
    fun deleteScreenRemovesItsItemsAndPrunesEmptyBatches() {
        val session = baseSession().copy(
            screens = listOf(screen("screen-1"), screen("screen-2")),
            items = listOf(item("item-1", "screen-1"), item("item-2", "screen-2")),
            handoffBatches = listOf(batch("batch-1", listOf("item-1"))),
        )

        val updated = SessionReducer.reduce(
            session,
            SessionMutation.DeleteScreen(screenId = "screen-1", now = 200L),
        )

        assertEquals(listOf("screen-2"), updated.screens.map { it.screenId })
        assertEquals(listOf("item-2"), updated.items.map { it.itemId })
        assertEquals(emptyList(), updated.handoffBatches)
        assertEquals(200L, updated.updatedAtEpochMillis)
    }

    @Test
    fun deleteItemRemovesItFromItemsAndPrunesEmptyBatches() {
        val session = baseSession().copy(
            items = listOf(item("item-1", "screen-1"), item("item-2", "screen-1")),
            handoffBatches = listOf(
                batch("batch-1", listOf("item-1")),
                batch("batch-2", listOf("item-1", "item-2")),
            ),
        )

        val updated = SessionReducer.reduce(
            session,
            SessionMutation.DeleteItem(itemId = "item-1", now = 250L),
        )

        assertEquals(listOf("item-2"), updated.items.map { it.itemId })
        assertEquals(listOf("batch-2"), updated.handoffBatches.map { it.batchId })
        assertEquals(listOf("item-2"), updated.handoffBatches.single().itemIds)
        assertEquals(250L, updated.updatedAtEpochMillis)
    }

    @Test
    fun addHandoffSetsReadyForAgentAndStoresBatch() {
        val sent = item("item-1", "screen-1").copy(delivery = FeedbackDelivery.SENT)
        val handoff = batch("batch-1", listOf("item-1"))

        val updated = SessionReducer.reduce(
            baseSession().copy(items = listOf(sent)),
            SessionMutation.AddHandoff(batch = handoff, items = listOf(sent), now = 300L),
        )

        assertEquals(SessionStatusDto.READY_FOR_AGENT, updated.status)
        assertEquals(listOf(sent), updated.items)
        assertEquals(listOf("batch-1"), updated.handoffBatches.map { it.batchId })
        assertEquals(300L, updated.updatedAtEpochMillis)
    }

    @Test
    fun closeMarksSessionClosedAndUpdatesTimestamp() {
        val session = baseSession().copy(status = SessionStatusDto.READY_FOR_AGENT)

        val updated = SessionReducer.reduce(
            session,
            SessionMutation.Close(now = 400L),
        )

        assertEquals(SessionStatusDto.CLOSED, updated.status)
        assertEquals(400L, updated.updatedAtEpochMillis)
    }

    private fun baseSession(): SessionDto = SessionDto(
        sessionId = "session-1",
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
    )

    private fun screen(id: String): SnapshotDto = SnapshotDto(
        screenId = id,
        capturedAtEpochMillis = 100L,
        displayName = "MainActivity",
    )

    private fun item(id: String, screenId: String): AnnotationDto = AnnotationDto(
        itemId = id,
        screenId = screenId,
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
        target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
        comment = "Fix spacing",
    )

    private fun batch(id: String, itemIds: List<String>): FeedbackHandoffBatch = FeedbackHandoffBatch(
        batchId = id,
        sequenceNumber = 1,
        createdAtEpochMillis = 100L,
        itemIds = itemIds,
        markdownSnapshot = null,
    )
}
