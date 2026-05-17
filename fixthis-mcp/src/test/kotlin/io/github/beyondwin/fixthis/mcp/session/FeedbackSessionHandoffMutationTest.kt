package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeedbackSessionHandoffMutationTest {
    @Test
    fun `draft target item becomes sent and ready`() {
        val session = sessionWithItems(
            item("item-1", delivery = FeedbackDelivery.DRAFT, status = AnnotationStatusDto.OPEN),
            item("item-2", delivery = FeedbackDelivery.DRAFT, status = AnnotationStatusDto.OPEN),
        )

        val result = FeedbackSessionHandoffMutation.prepare(
            session = session,
            targetItemIds = listOf("item-1"),
            markdownSnapshot = "handoff",
            now = 200L,
            batchId = { "batch-1" },
        )

        val updated = result!!.session
        val sent = updated.items.single { it.itemId == "item-1" }
        val untouched = updated.items.single { it.itemId == "item-2" }

        assertEquals(listOf("item-1"), result.batch.itemIds)
        assertEquals("batch-1", sent.handoffBatchId)
        assertEquals(200L, sent.sentAtEpochMillis)
        assertEquals(200L, sent.lastHandedOffAtEpochMillis)
        assertEquals(FeedbackDelivery.SENT, sent.delivery)
        assertEquals(AnnotationStatusDto.READY, sent.status)
        assertEquals(FeedbackDelivery.DRAFT, untouched.delivery)
        assertEquals(SessionStatusDto.READY_FOR_AGENT, updated.status)
    }

    @Test
    fun `sent ready target can be handed off again while preserving sent timestamp`() {
        val session = sessionWithItems(
            item("item-1", delivery = FeedbackDelivery.SENT, status = AnnotationStatusDto.READY),
            item("item-2", delivery = FeedbackDelivery.DRAFT, status = AnnotationStatusDto.OPEN),
        )

        val result = FeedbackSessionHandoffMutation.prepare(
            session = session,
            targetItemIds = listOf("item-1"),
            markdownSnapshot = "handoff-again",
            now = 300L,
            batchId = { "batch-2" },
        )

        val handedOffAgain = result!!.session.items.single { it.itemId == "item-1" }
        val untouchedDraft = result.session.items.single { it.itemId == "item-2" }

        assertEquals(listOf("item-1"), result.batch.itemIds)
        assertEquals(100L, handedOffAgain.sentAtEpochMillis)
        assertEquals(300L, handedOffAgain.lastHandedOffAtEpochMillis)
        assertEquals("batch-2", handedOffAgain.handoffBatchId)
        assertEquals(FeedbackDelivery.DRAFT, untouchedDraft.delivery)
    }

    @Test
    fun `no matching candidates returns null`() {
        val session = sessionWithItems(
            item("item-1", delivery = FeedbackDelivery.DRAFT, status = AnnotationStatusDto.OPEN),
        )

        val result = FeedbackSessionHandoffMutation.prepare(
            session = session,
            targetItemIds = listOf("missing"),
            markdownSnapshot = "handoff",
            now = 200L,
            batchId = { error("batch id should not be generated without candidates") },
        )

        assertNull(result)
    }

    private fun sessionWithItems(vararg items: AnnotationDto): SessionDto = SessionDto(
        sessionId = "session-1",
        packageName = "pkg",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(screen()),
        items = items.toList(),
    )

    private fun screen(): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 1L,
        activityName = "MainActivity",
        displayName = "MainActivity",
    )

    private fun item(
        id: String,
        delivery: FeedbackDelivery,
        status: AnnotationStatusDto,
    ): AnnotationDto = AnnotationDto(
        itemId = id,
        screenId = "screen-1",
        createdAtEpochMillis = 10L,
        updatedAtEpochMillis = 10L,
        target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
        comment = "Fix spacing",
        delivery = delivery,
        status = status,
        sentAtEpochMillis = if (delivery == FeedbackDelivery.SENT) 100L else null,
    )
}
