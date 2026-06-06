package io.github.beyondwin.fixthis.mcp.session.lifecycle.event

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackHandoffBatch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden byte-identity tests for [SessionEventPayloadFactory].
 *
 * Each test reconstructs the EXACT payload-building logic the
 * [io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStoreDelegate]
 * used before extraction (the inline `buildJsonObject { ... }` blocks plus the
 * module-level `eventLogJson` config: `encodeDefaults = true`,
 * `ignoreUnknownKeys = true`) and asserts the factory's serialized output is
 * BYTE-IDENTICAL. The event-log JSON is a persisted compatibility contract
 * (CLAUDE.md) — field names, ordering, and number/string formatting must not drift.
 */
class SessionEventPayloadFactoryTest {
    // Pre-extraction baseline: a private copy of the delegate's module-level `eventLogJson`.
    private val goldenJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun goldenString(payload: JsonObject): String = payload.toString()

    @Test
    fun `screen payload is byte-identical to the old delegate path`() {
        val screen = snapshot()
        val golden = buildJsonObject {
            put("sessionId", SESSION_ID)
            put("screen", goldenJson.encodeToJsonElement(SnapshotDto.serializer(), screen))
        }

        val actual = SessionEventPayloadFactory.screen(SESSION_ID, screen)

        assertEquals(goldenString(golden), goldenString(actual))
    }

    @Test
    fun `screenWithItems payload is byte-identical to the old delegate path`() {
        val screen = snapshot()
        val items = listOf(item("item-1"), item("item-2"))
        val eventMetadata = buildJsonObject {
            put("origin", "console")
            put("attempt", 3)
        }
        val golden = buildJsonObject {
            put("sessionId", SESSION_ID)
            eventMetadata.forEach { (key, value) -> put(key, value) }
            put("screen", goldenJson.encodeToJsonElement(SnapshotDto.serializer(), screen))
            put(
                "items",
                goldenJson.encodeToJsonElement(ListSerializer(AnnotationDto.serializer()), items),
            )
        }

        val actual = SessionEventPayloadFactory.screenWithItems(
            sessionId = SESSION_ID,
            eventMetadata = eventMetadata,
            screen = screen,
            items = items,
        )

        assertEquals(goldenString(golden), goldenString(actual))
    }

    @Test
    fun `screenWithItems payload with empty metadata is byte-identical`() {
        val screen = snapshot()
        val items = listOf(item("item-1"))
        val golden = buildJsonObject {
            put("sessionId", SESSION_ID)
            put("screen", goldenJson.encodeToJsonElement(SnapshotDto.serializer(), screen))
            put(
                "items",
                goldenJson.encodeToJsonElement(ListSerializer(AnnotationDto.serializer()), items),
            )
        }

        val actual = SessionEventPayloadFactory.screenWithItems(
            sessionId = SESSION_ID,
            eventMetadata = JsonObject(emptyMap()),
            screen = screen,
            items = items,
        )

        assertEquals(goldenString(golden), goldenString(actual))
    }

    @Test
    fun `item payload is byte-identical to the old delegate path`() {
        val created = item("item-9")
        val golden = buildJsonObject {
            put("sessionId", SESSION_ID)
            put("item", goldenJson.encodeToJsonElement(AnnotationDto.serializer(), created))
        }

        val actual = SessionEventPayloadFactory.item(SESSION_ID, created)

        assertEquals(goldenString(golden), goldenString(actual))
    }

    @Test
    fun `items payload is byte-identical to the old delegate path`() {
        val items = listOf(item("item-1"), item("item-2"))
        val golden = buildJsonObject {
            put("sessionId", SESSION_ID)
            put(
                "items",
                goldenJson.encodeToJsonElement(ListSerializer(AnnotationDto.serializer()), items),
            )
        }

        val actual = SessionEventPayloadFactory.items(SESSION_ID, items)

        assertEquals(goldenString(golden), goldenString(actual))
    }

    @Test
    fun `deleteScreen payload is byte-identical to the old delegate path`() {
        val golden = buildJsonObject {
            put("sessionId", SESSION_ID)
            put("screenId", "screen-1")
        }

        val actual = SessionEventPayloadFactory.deleteScreen(SESSION_ID, "screen-1")

        assertEquals(goldenString(golden), goldenString(actual))
    }

    @Test
    fun `deleteItem payload is byte-identical to the old delegate path`() {
        val golden = buildJsonObject {
            put("sessionId", SESSION_ID)
            put("itemId", "item-1")
        }

        val actual = SessionEventPayloadFactory.deleteItem(SESSION_ID, "item-1")

        assertEquals(goldenString(golden), goldenString(actual))
    }

    @Test
    fun `updateDraftItems payload is byte-identical to the old delegate path`() {
        val items = listOf(item("item-1"), item("item-2"))
        val golden = buildJsonObject {
            put("sessionId", SESSION_ID)
            put(
                "items",
                goldenJson.encodeToJsonElement(ListSerializer(AnnotationDto.serializer()), items),
            )
        }

        val actual = SessionEventPayloadFactory.updateDraftItems(SESSION_ID, items)

        assertEquals(goldenString(golden), goldenString(actual))
    }

    @Test
    fun `handoff payload is byte-identical to the old delegate path`() {
        val items = listOf(item("item-1"))
        val batch = FeedbackHandoffBatch(
            batchId = "batch-1",
            sequenceNumber = 1,
            createdAtEpochMillis = 3L,
            itemIds = listOf("item-1"),
            markdownSnapshot = "handoff",
        )
        val golden = buildJsonObject {
            put("sessionId", SESSION_ID)
            put("batch", goldenJson.encodeToJsonElement(FeedbackHandoffBatch.serializer(), batch))
            put(
                "items",
                goldenJson.encodeToJsonElement(ListSerializer(AnnotationDto.serializer()), items),
            )
        }

        val actual = SessionEventPayloadFactory.handoff(SESSION_ID, batch, items)

        assertEquals(goldenString(golden), goldenString(actual))
    }

    private fun snapshot(): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 1L,
        activityName = "MainActivity",
        displayName = "MainActivity",
    )

    private fun item(itemId: String): AnnotationDto = AnnotationDto(
        itemId = itemId,
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
        comment = "Fix",
    )

    private companion object {
        const val SESSION_ID = "session-1"
    }
}
