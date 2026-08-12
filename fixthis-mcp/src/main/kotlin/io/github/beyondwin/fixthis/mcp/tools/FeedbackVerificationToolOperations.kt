package io.github.beyondwin.fixthis.mcp.tools

import io.github.beyondwin.fixthis.mcp.McpProtocol
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionKind
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationReceiptDto
import io.github.beyondwin.fixthis.mcp.textContent
import io.github.beyondwin.fixthis.mcp.toolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal class FeedbackVerificationToolOperations(
    private val feedbackService: FeedbackSessionService,
) {
    suspend fun verifyFeedback(arguments: JsonObject): JsonObject = bridgeToolResult {
        val session = requestedSession(arguments)
        val itemId = arguments.stringParam("itemId")?.takeIf { it.isNotBlank() }
            ?: throw FixThisToolException("fixthis_verify_feedback requires itemId")
        val receipt = feedbackService.verifyFeedback(
            sessionId = session.sessionId,
            itemId = itemId,
            assertions = arguments.verificationAssertions(),
        )
        val receiptJson = McpProtocol.json.encodeToJsonElement(
            FeedbackVerificationReceiptDto.serializer(),
            receipt,
        )
        toolResult(
            content = listOf(
                textContent(
                    "Feedback item $itemId verification: ${receipt.verdict.name} " +
                        "(receipt ${receipt.receiptId})",
                ),
            ),
            structuredContent = buildJsonObject { put("receipt", receiptJson) },
        )
    }

    private fun JsonObject.verificationAssertions(): List<FeedbackVerificationAssertionDto> {
        val assertions = this["assertions"] ?: return emptyList()
        val array = assertions as? JsonArray
            ?: verificationAssertionsError("assertions must be an array")
        return array.mapIndexed { index, element ->
            val assertion = element as? JsonObject
                ?: verificationAssertionsError("assertions[$index] must be an object")
            val unsupported = assertion.keys - assertionFields
            if (unsupported.isNotEmpty()) {
                verificationAssertionsError("assertions[$index] has unsupported field ${unsupported.first()}")
            }
            FeedbackVerificationAssertionDto(
                kind = assertion.requiredAssertionKind(index),
                value = assertion.optionalAssertionString(index, "value"),
                role = assertion.optionalAssertionString(index, "role"),
            )
        }
    }

    private fun JsonObject.requiredAssertionKind(index: Int): FeedbackVerificationAssertionKind = when (
        optionalAssertionString(index, "kind")
    ) {
        "text_present" -> FeedbackVerificationAssertionKind.TEXT_PRESENT
        "text_absent" -> FeedbackVerificationAssertionKind.TEXT_ABSENT
        "target_present" -> FeedbackVerificationAssertionKind.TARGET_PRESENT
        null -> verificationAssertionsError("assertions[$index] requires kind")
        else -> verificationAssertionsError("assertions[$index] has unsupported kind")
    }

    private fun JsonObject.optionalAssertionString(index: Int, name: String): String? {
        val value = this[name] ?: return null
        return (value as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?: verificationAssertionsError("assertions[$index].$name must be a string")
    }

    private fun requestedSession(arguments: JsonObject): SessionDto {
        val sessionId = arguments.stringParam("sessionId")?.takeIf { it.isNotBlank() }
        return if (sessionId == null) feedbackService.currentSession() else feedbackService.getSession(sessionId)
    }

    private fun verificationAssertionsError(detail: String): Nothing = throw FixThisToolException(
        "VERIFICATION_ASSERTIONS_INVALID: $detail",
    )

    private companion object {
        val assertionFields = setOf("kind", "value", "role")
    }
}
