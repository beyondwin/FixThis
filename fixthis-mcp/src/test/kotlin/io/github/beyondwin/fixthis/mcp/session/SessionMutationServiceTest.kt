package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackHandoffBatch
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionMutationService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionMutationServiceTest {
    @Test
    fun addItemAssignsStableSequenceAndRejectsUnknownScreen() {
        val mutations = SessionMutationService(clock = { 100L }, idGenerator = { "item-1" })
        val session = session().copy(screens = listOf(screen("screen-1")))

        val (updated, created) = mutations.addItem(
            session,
            item("pending", screenId = "screen-1", sequenceNumber = null),
        )

        assertEquals("item-1", created.itemId)
        assertEquals(1, created.sequenceNumber)
        assertEquals(2, updated.nextItemSequenceNumber)
        assertFailsWith<IllegalArgumentException> {
            mutations.addItem(session, item("pending", screenId = "missing"))
        }
    }

    @Test
    fun deleteScreenRemovesItemsAndEmptyHandoffBatches() {
        val mutations = SessionMutationService(clock = { 200L }, idGenerator = { "unused" })
        val session = session().copy(
            screens = listOf(screen("screen-1"), screen("screen-2")),
            items = listOf(item("item-1", "screen-1"), item("item-2", "screen-2")),
            handoffBatches = listOf(
                FeedbackHandoffBatch("batch-1", 1, 10L, itemIds = listOf("item-1")),
                FeedbackHandoffBatch("batch-2", 2, 11L, itemIds = listOf("item-1", "item-2")),
            ),
        )

        val updated = mutations.deleteScreen(session, "screen-1")

        assertEquals(listOf("screen-2"), updated.screens.map { it.screenId })
        assertEquals(listOf("item-2"), updated.items.map { it.itemId })
        assertEquals(listOf("batch-2"), updated.handoffBatches.map { it.batchId })
        assertEquals(listOf("item-2"), updated.handoffBatches.single().itemIds)
        assertEquals(200L, updated.updatedAtEpochMillis)
    }

    private fun session(): SessionDto = SessionDto(
        sessionId = "session-1",
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun screen(id: String): SnapshotDto = SnapshotDto(
        screenId = id,
        capturedAtEpochMillis = 2L,
        displayName = "MainActivity",
    )

    private fun item(id: String, screenId: String, sequenceNumber: Int? = 1): AnnotationDto = AnnotationDto(
        itemId = id,
        screenId = screenId,
        createdAtEpochMillis = 3L,
        updatedAtEpochMillis = 3L,
        target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
        comment = "Fix spacing",
        sequenceNumber = sequenceNumber,
    )
}
