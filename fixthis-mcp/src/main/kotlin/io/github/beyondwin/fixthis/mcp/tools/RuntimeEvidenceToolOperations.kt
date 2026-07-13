package io.github.beyondwin.fixthis.mcp.tools

import io.github.beyondwin.fixthis.mcp.McpProtocol
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceCaptureRequest
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceCaptureResult
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePreset
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceTrigger
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal class RuntimeEvidenceToolOperations(
    private val feedbackService: FeedbackSessionService,
) {
    suspend fun attachSummary(arguments: JsonObject): JsonObject = bridgeToolResult {
        val session = requestedLegacySession(arguments)
        val itemId = arguments.requiredString("itemId", "fixthis_capture_runtime_evidence")
        val type = arguments.requiredString("type", "fixthis_capture_runtime_evidence").toEvidenceType()
        val summary = arguments.requiredString("summary", "fixthis_capture_runtime_evidence")
        val updated = feedbackService.captureRuntimeEvidence(
            sessionId = session.sessionId,
            itemId = itemId,
            type = type,
            summary = summary,
            artifactPath = arguments.stringParam("artifactPath"),
        )
        jsonToolResult(McpProtocol.json.encodeToJsonElement(SessionDto.serializer(), updated).jsonObject)
    }

    suspend fun collect(arguments: JsonObject): JsonObject = bridgeToolResult {
        arguments.requireOnly("sessionId", "itemId", "preset")
        val session = requestedSession(arguments)
        val itemId = arguments.requiredString("itemId", "fixthis_collect_runtime_evidence")
        val preset = arguments.requiredString("preset", "fixthis_collect_runtime_evidence").toPreset()
        val item = session.items.firstOrNull { it.itemId == itemId }
            ?: throw FixThisToolException("Unknown feedback item: $itemId")
        val result = feedbackService.collectRuntimeEvidence(
            RuntimeEvidenceCaptureRequest(
                sessionId = session.sessionId,
                itemIds = listOf(item.itemId),
                screenId = item.screenId,
                preset = preset,
                trigger = RuntimeEvidenceTrigger.MCP_MANUAL,
            ),
        )
        jsonToolResult(
            McpProtocol.json.encodeToJsonElement(RuntimeEvidenceCaptureResult.serializer(), result).jsonObject,
        )
    }

    private fun requestedSession(arguments: JsonObject): SessionDto {
        val rawSessionId = arguments.stringParam("sessionId")
        if (rawSessionId != null && rawSessionId.isBlank()) {
            throw FixThisToolException("sessionId must not be blank")
        }
        return rawSessionId?.let(feedbackService::getSession) ?: feedbackService.currentSession()
    }

    private fun requestedLegacySession(arguments: JsonObject): SessionDto {
        val sessionId = arguments.stringParam("sessionId")?.takeIf { it.isNotBlank() }
        return sessionId?.let(feedbackService::getSession) ?: feedbackService.currentSession()
    }

    private fun JsonObject.requiredString(name: String, tool: String): String = stringParam(name)?.takeIf { it.isNotBlank() } ?: throw FixThisToolException("$tool requires $name")

    private fun JsonObject.requireOnly(vararg allowed: String) {
        val extra = keys - allowed.toSet()
        if (extra.isNotEmpty()) throw FixThisToolException("Unsupported runtime evidence argument: ${extra.first()}")
    }

    private fun String.toPreset(): RuntimeEvidencePreset = when (this) {
        "baseline" -> RuntimeEvidencePreset.BASELINE
        "logs" -> RuntimeEvidencePreset.LOGS
        "memory" -> RuntimeEvidencePreset.MEMORY
        "performance" -> RuntimeEvidencePreset.PERFORMANCE
        else -> throw FixThisToolException("Unsupported runtime evidence preset: $this")
    }

    private fun String.toEvidenceType(): RuntimeEvidenceType = when (this) {
        "logcat_window" -> RuntimeEvidenceType.LOGCAT_WINDOW
        "frame_summary" -> RuntimeEvidenceType.FRAME_SUMMARY
        "memory_summary" -> RuntimeEvidenceType.MEMORY_SUMMARY
        "trace_artifact" -> RuntimeEvidenceType.TRACE_ARTIFACT
        else -> throw FixThisToolException("Unsupported runtime evidence type: $this")
    }
}
