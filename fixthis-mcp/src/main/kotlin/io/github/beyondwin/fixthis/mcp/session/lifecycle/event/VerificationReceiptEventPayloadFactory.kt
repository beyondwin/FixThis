package io.github.beyondwin.fixthis.mcp.session.lifecycle.event

import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationReceiptDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val verificationReceiptEventJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun SessionEventPayloadFactory.verificationReceipt(
    sessionId: String,
    receipt: FeedbackVerificationReceiptDto,
): JsonObject = buildJsonObject {
    put("sessionId", sessionId)
    put(
        "receipt",
        verificationReceiptEventJson.encodeToJsonElement(
            FeedbackVerificationReceiptDto.serializer(),
            receipt,
        ),
    )
}
