package io.github.beyondwin.fixthis.mcp.session.lifecycle.event

import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackHandoffBatch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * JSON configuration used to serialize every event-log payload.
 *
 * INVARIANT: the resulting JSON is a persisted compatibility contract — field
 * names, structure, ordering, and number/string formatting are replayed on boot
 * (CLAUDE.md). `encodeDefaults`/`ignoreUnknownKeys` directly affect the emitted
 * bytes, so this config must not change without a coordinated migration.
 */
private val eventLogJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Single source of truth for mutation → event-log [JsonObject] payload serialization.
 *
 * Each function builds the payload for one mutation intent. The serialized output
 * must remain byte-identical across refactors because the event log is persisted
 * and replayed.
 */
internal object SessionEventPayloadFactory {
    fun screen(sessionId: String, screen: SnapshotDto): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        putScreen(screen)
    }

    fun screenWithItems(
        sessionId: String,
        eventMetadata: JsonObject,
        screen: SnapshotDto,
        items: List<AnnotationDto>,
    ): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        eventMetadata.forEach { (key, value) -> put(key, value) }
        putScreen(screen)
        putItems(items)
    }

    fun item(sessionId: String, item: AnnotationDto): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("item", eventLogJson.encodeToJsonElement(AnnotationDto.serializer(), item))
    }

    fun items(sessionId: String, items: List<AnnotationDto>): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        putItems(items)
    }

    fun deleteScreen(sessionId: String, screenId: String): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("screenId", screenId)
    }

    fun deleteItem(sessionId: String, itemId: String): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("itemId", itemId)
    }

    fun updateDraftItems(sessionId: String, items: List<AnnotationDto>): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        putItems(items)
    }

    fun handoff(
        sessionId: String,
        batch: FeedbackHandoffBatch,
        updatedItems: List<AnnotationDto>,
    ): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("batch", eventLogJson.encodeToJsonElement(FeedbackHandoffBatch.serializer(), batch))
        putItems(updatedItems)
    }

    private fun JsonObjectBuilder.putScreen(screen: SnapshotDto) {
        put("screen", eventLogJson.encodeToJsonElement(SnapshotDto.serializer(), screen))
    }

    private fun JsonObjectBuilder.putItems(items: List<AnnotationDto>) {
        put(
            "items",
            eventLogJson.encodeToJsonElement(ListSerializer(AnnotationDto.serializer()), items),
        )
    }
}
