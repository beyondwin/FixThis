package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.beyondwin.fixthis.mcp.session.SessionDto
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun ConsoleEventBus.emitSessionUpdated(session: SessionDto) {
    emit(
        "session-updated",
        buildJsonObject {
            put("sessionId", session.sessionId)
            put("session", fixThisJson.encodeToJsonElement(SessionDto.serializer(), session).jsonObject)
        },
    )
    emit(
        "sessions-updated",
        buildJsonObject {
            put("sessionId", session.sessionId)
        },
    )
}

internal fun ConsoleEventBus.emitPreviewReady(
    sessionId: String,
    preview: FeedbackPreviewSnapshot,
) {
    emit(
        "preview-ready",
        buildJsonObject {
            put("sessionId", sessionId)
            put(
                "preview",
                fixThisJson.encodeToJsonElement(
                    FeedbackPreviewSnapshot.serializer(),
                    preview,
                ).jsonObject,
            )
        },
    )
}
