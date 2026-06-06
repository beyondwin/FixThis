package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionEventPayloadsTest {
    @Test
    fun `handoff payload keeps session batch and items keys`() {
        val item = item()
        val batch = FeedbackHandoffBatch(
            batchId = "batch-1",
            sequenceNumber = 1,
            createdAtEpochMillis = 3L,
            itemIds = listOf("item-1"),
            markdownSnapshot = "handoff",
        )

        val payload = SessionEventPayloads.handoff(
            sessionId = "session-1",
            batch = batch,
            updatedItems = listOf(item),
        )

        assertEquals("session-1", payload.getValue("sessionId").jsonPrimitive.content)
        assertEquals("batch-1", payload.getValue("batch").jsonObject.getValue("batchId").jsonPrimitive.content)
        assertEquals(
            "item-1",
            payload.getValue("items").jsonArray.single().jsonObject.getValue("itemId").jsonPrimitive.content,
        )
    }

    @Test
    fun `screen payload keeps session and screen keys`() {
        val payload = SessionEventPayloads.screen(
            sessionId = "session-1",
            screen = SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 1L,
                activityName = "MainActivity",
                displayName = "MainActivity",
            ),
        )

        assertEquals("session-1", payload.getValue("sessionId").jsonPrimitive.content)
        assertEquals("screen-1", payload.getValue("screen").jsonObject.getValue("screenId").jsonPrimitive.content)
    }

    @Test
    fun `items payload keeps session and item keys`() {
        val payload = SessionEventPayloads.items(
            sessionId = "session-1",
            items = listOf(item()),
        )

        assertEquals("session-1", payload.getValue("sessionId").jsonPrimitive.content)
        assertEquals(
            "item-1",
            payload.getValue("items").jsonArray.single().jsonObject.getValue("itemId").jsonPrimitive.content,
        )
    }

    private fun item(): AnnotationDto = AnnotationDto(
        itemId = "item-1",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
        comment = "Fix",
    )
}
