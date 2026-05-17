package io.github.beyondwin.fixthis.mcp.session

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val sessionEventPayloadJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal object SessionEventPayloads {
    fun screen(sessionId: String, screen: SnapshotDto): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("screen", sessionEventPayloadJson.encodeToJsonElement(SnapshotDto.serializer(), screen))
    }

    fun items(sessionId: String, items: List<AnnotationDto>): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put(
            "items",
            sessionEventPayloadJson.encodeToJsonElement(
                ListSerializer(AnnotationDto.serializer()),
                items,
            ),
        )
    }

    fun handoff(
        sessionId: String,
        batch: FeedbackHandoffBatch,
        updatedItems: List<AnnotationDto>,
    ): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("batch", sessionEventPayloadJson.encodeToJsonElement(FeedbackHandoffBatch.serializer(), batch))
        put(
            "items",
            sessionEventPayloadJson.encodeToJsonElement(
                ListSerializer(AnnotationDto.serializer()),
                updatedItems,
            ),
        )
    }
}
