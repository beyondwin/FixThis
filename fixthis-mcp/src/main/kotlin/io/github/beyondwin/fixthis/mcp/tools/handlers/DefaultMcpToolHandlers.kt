package io.github.beyondwin.fixthis.mcp.tools.handlers

import io.github.beyondwin.fixthis.mcp.tools.FeedbackToolOperations
import io.github.beyondwin.fixthis.mcp.tools.RuntimeEvidenceToolOperations
import io.github.beyondwin.fixthis.mcp.tools.ScreenToolOperations
import kotlinx.serialization.json.JsonObject

internal fun defaultMcpToolHandlers(
    screenOps: ScreenToolOperations,
    feedbackOps: FeedbackToolOperations,
    runtimeEvidenceOps: RuntimeEvidenceToolOperations,
): List<McpToolHandler> = listOf(
    OperationBackedToolHandler("fixthis_status", screenOps::status),
    OperationBackedToolHandler("fixthis_get_current_screen", screenOps::getCurrentScreen),
    OperationBackedToolHandler("fixthis_verify_ui_change", screenOps::verifyUiChange),
    OperationBackedToolHandler("fixthis_open_feedback_console", feedbackOps::openFeedbackConsole),
    OperationBackedToolHandler("fixthis_list_feedback_sessions", feedbackOps::listFeedbackSessions),
    OperationBackedToolHandler("fixthis_capture_screen", feedbackOps::captureScreen),
    OperationBackedToolHandler("fixthis_navigate_app", feedbackOps::navigateApp),
    OperationBackedToolHandler("fixthis_list_feedback", feedbackOps::listFeedback),
    OperationBackedToolHandler("fixthis_read_feedback", feedbackOps::readFeedback),
    OperationBackedToolHandler("fixthis_resolve_feedback", feedbackOps::resolveFeedback),
    OperationBackedToolHandler("fixthis_claim_feedback", feedbackOps::claimFeedback),
    OperationBackedToolHandler("fixthis_capture_runtime_evidence", runtimeEvidenceOps::attachSummary),
    OperationBackedToolHandler("fixthis_collect_runtime_evidence", runtimeEvidenceOps::collect),
)

private class OperationBackedToolHandler(
    override val name: String,
    private val operation: suspend (JsonObject) -> JsonObject,
) : McpToolHandler {
    override suspend fun handle(arguments: JsonObject): JsonObject = operation(arguments)
}
