package io.github.beyondwin.fixthis.mcp.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val RESOLVE_FEEDBACK_DESCRIPTION =
    "Mark a feedback item as resolved, needing clarification, or not fixed. " +
        "Call this after claiming an item with fixthis_claim_feedback and finishing the work. " +
        "Status must be one of resolved, needs_clarification, wont_fix."
private const val VERIFY_FEEDBACK_DESCRIPTION =
    "Verify one claimed feedback item against the current debug app and persist a bounded receipt. " +
        "This never resolves the item automatically."
private const val MAX_VERIFICATION_ASSERTIONS = 8
private const val MAX_ASSERTION_VALUE_LENGTH = 256
private const val MAX_ASSERTION_ROLE_LENGTH = 64

internal fun verifyFeedbackToolDefinition(): ToolDefinition = ToolDefinition(
    name = "fixthis_verify_feedback",
    description = VERIFY_FEEDBACK_DESCRIPTION,
    inputSchema = objectSchema(
        "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
        "itemId" to stringProperty("Claimed feedback item id to verify."),
        "assertions" to assertionArraySchema(),
        required = listOf("itemId"),
    ),
)

internal fun resolveFeedbackToolDefinition(): ToolDefinition = ToolDefinition(
    name = "fixthis_resolve_feedback",
    description = RESOLVE_FEEDBACK_DESCRIPTION,
    inputSchema = objectSchema(
        "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
        "itemId" to stringProperty("Feedback item id to update."),
        "status" to stringProperty("One of resolved, needs_clarification, or wont_fix."),
        "summary" to stringProperty("Agent summary shown in the console."),
        "verificationReceiptId" to stringProperty(
            "Optional latest PASS or WARN receipt to link when status is resolved.",
        ),
        required = listOf("itemId", "status"),
    ),
)

private fun assertionArraySchema(): JsonObject = buildJsonObject {
    put("type", "array")
    put("maxItems", MAX_VERIFICATION_ASSERTIONS)
    putJsonObject("items") {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("kind") {
                put("type", "string")
                putJsonArray("enum") {
                    add(JsonPrimitive("text_present"))
                    add(JsonPrimitive("text_absent"))
                    add(JsonPrimitive("target_present"))
                }
            }
            putJsonObject("value") {
                put("type", "string")
                put("maxLength", MAX_ASSERTION_VALUE_LENGTH)
            }
            putJsonObject("role") {
                put("type", "string")
                put("maxLength", MAX_ASSERTION_ROLE_LENGTH)
            }
        }
        putJsonArray("required") { add(JsonPrimitive("kind")) }
        put("additionalProperties", false)
    }
}
