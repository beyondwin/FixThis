package io.github.beyondwin.fixthis.mcp.tools.handlers

import io.github.beyondwin.fixthis.mcp.tools.FixThisToolOperations
import kotlinx.serialization.json.JsonObject

internal fun defaultMcpToolHandlers(operations: FixThisToolOperations): List<McpToolHandler> = listOf(
    OperationBackedToolHandler("fixthis_status", operations::status),
    OperationBackedToolHandler("fixthis_get_current_screen", operations::getCurrentScreen),
    OperationBackedToolHandler("fixthis_verify_ui_change", operations::verifyUiChange),
    OperationBackedToolHandler("fixthis_open_feedback_console", operations::openFeedbackConsole),
    OperationBackedToolHandler("fixthis_list_feedback_sessions", operations::listFeedbackSessions),
    OperationBackedToolHandler("fixthis_capture_screen", operations::captureScreen),
    OperationBackedToolHandler("fixthis_navigate_app", operations::navigateApp),
    OperationBackedToolHandler("fixthis_list_feedback", operations::listFeedback),
    OperationBackedToolHandler("fixthis_read_feedback", operations::readFeedback),
    OperationBackedToolHandler("fixthis_resolve_feedback", operations::resolveFeedback),
    OperationBackedToolHandler("fixthis_claim_feedback", operations::claimFeedback),
)

private class OperationBackedToolHandler(
    override val name: String,
    private val operation: suspend (JsonObject) -> JsonObject,
) : McpToolHandler {
    override suspend fun handle(arguments: JsonObject): JsonObject = operation(arguments)
}
