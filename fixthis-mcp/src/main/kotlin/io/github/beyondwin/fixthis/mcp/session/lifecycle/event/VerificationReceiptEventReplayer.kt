package io.github.beyondwin.fixthis.mcp.session.lifecycle.event

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.SessionEvent
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationReceiptDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

private val verificationReceiptReplayJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal object VerificationReceiptEventReplayer {
    fun apply(session: SessionDto, event: SessionEvent): SessionDto {
        require(event.type == "feedbackVerified") {
            "Unsupported verification-receipt event type: ${event.type}"
        }
        val payload = verificationReceiptReplayJson.decodeFromJsonElement(
            FeedbackVerifiedPayload.serializer(),
            event.payload,
        )
        return SessionReducer.reduce(
            session,
            SessionMutation.AttachVerificationReceipt(payload.receipt, event.epochMillis),
        )
    }
}

@Serializable
private data class FeedbackVerifiedPayload(
    val receipt: FeedbackVerificationReceiptDto,
)
