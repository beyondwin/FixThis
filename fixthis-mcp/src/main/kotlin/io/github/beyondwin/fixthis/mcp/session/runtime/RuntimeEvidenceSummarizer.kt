package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceKind
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceResult
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceStatus
import io.github.beyondwin.fixthis.cli.runtime.RuntimeEvidenceParsers

internal data class RuntimeEvidenceSummary(
    val type: RuntimeEvidenceType,
    val status: RuntimeEvidenceStatus,
    val summary: String,
    val warnings: Set<RuntimeEvidenceWarning>,
    val failureReason: RuntimeEvidenceFailureReason?,
)

internal class RuntimeEvidenceSummarizer(
    private val redactor: RuntimeEvidenceRedactor,
) {
    fun summarize(
        result: CliRuntimeEvidenceResult,
        screenCapturedAtEpochMillis: Long,
    ): RuntimeEvidenceSummary {
        val rawSummary = when (result.kind) {
            CliRuntimeEvidenceKind.LOGCAT_WINDOW -> logcatSummary(result, screenCapturedAtEpochMillis)
            CliRuntimeEvidenceKind.MEMORY_SUMMARY -> memorySummary(result)
            CliRuntimeEvidenceKind.FRAME_SUMMARY -> frameSummary(result)
            CliRuntimeEvidenceKind.CONTEXT -> contextSummary(result)
        }
        val sourceRedacted = redactor.redact(result.output).redacted
        val redacted = redactor.redact(rawSummary)
        val warnings = result.warnings.mapNotNullTo(linkedSetOf(), ::warningFromCode)
        if (result.kind == CliRuntimeEvidenceKind.FRAME_SUMMARY) {
            warnings += RuntimeEvidenceWarning.CUMULATIVE_NOT_WINDOWED
        }
        if (result.kind == CliRuntimeEvidenceKind.LOGCAT_WINDOW) {
            warnings += RuntimeEvidenceWarning.SENSITIVE_LOGS_POSSIBLE
        }
        if (sourceRedacted || redacted.redacted) warnings += RuntimeEvidenceWarning.REDACTION_APPLIED
        return RuntimeEvidenceSummary(
            type = result.kind.toEvidenceType(),
            status = result.status.toEvidenceStatus(),
            summary = redacted.text.take(MAX_SUMMARY_CHARS),
            warnings = warnings,
            failureReason = failureFromCode(result.failureCode),
        )
    }

    private fun logcatSummary(result: CliRuntimeEvidenceResult, screenCapturedAt: Long): String {
        val signals = exceptionPattern.findAll(result.output).toList()
        val distance = result.startedAtEpochMillis - screenCapturedAt
        if (signals.isEmpty()) {
            return "no matching error pattern in the selected window; capture started $distance ms from frozen screen"
        }
        val first = signals.first()
        val exceptionClass = first.groups["exception"]?.value?.substringAfterLast('.') ?: "exception"
        val tag = tagPattern.find(result.output)?.groups?.get("tag")?.value ?: "unknown-tag"
        return "$exceptionClass tag=$tag count=${signals.size}; capture started $distance ms from frozen screen; causal link unproven"
    }

    private fun memorySummary(result: CliRuntimeEvidenceResult): String {
        val pss = RuntimeEvidenceParsers.totalPssKilobytes(result.output)
        return if (pss == null) "PSS unavailable in bounded memory summary" else "total PSS $pss KiB"
    }

    private fun frameSummary(result: CliRuntimeEvidenceResult): String {
        val janky = RuntimeEvidenceParsers.jankyFrameCount(result.output)
        return if (janky == null) {
            "janky frame count unavailable; gfxinfo is cumulative, not windowed"
        } else {
            "janky frames $janky; gfxinfo is cumulative, not windowed"
        }
    }

    private fun contextSummary(result: CliRuntimeEvidenceResult): String = "bounded runtime context status=${result.status.name.lowercase()}"

    private fun CliRuntimeEvidenceKind.toEvidenceType(): RuntimeEvidenceType = when (this) {
        CliRuntimeEvidenceKind.LOGCAT_WINDOW -> RuntimeEvidenceType.LOGCAT_WINDOW
        CliRuntimeEvidenceKind.MEMORY_SUMMARY -> RuntimeEvidenceType.MEMORY_SUMMARY
        CliRuntimeEvidenceKind.FRAME_SUMMARY -> RuntimeEvidenceType.FRAME_SUMMARY
        CliRuntimeEvidenceKind.CONTEXT -> RuntimeEvidenceType.TRACE_ARTIFACT
    }

    private fun CliRuntimeEvidenceStatus.toEvidenceStatus(): RuntimeEvidenceStatus = when (this) {
        CliRuntimeEvidenceStatus.COMPLETE -> RuntimeEvidenceStatus.COMPLETE
        CliRuntimeEvidenceStatus.PARTIAL -> RuntimeEvidenceStatus.PARTIAL
        CliRuntimeEvidenceStatus.FAILED -> RuntimeEvidenceStatus.FAILED
        CliRuntimeEvidenceStatus.UNSUPPORTED -> RuntimeEvidenceStatus.UNSUPPORTED
    }

    private companion object {
        const val MAX_SUMMARY_CHARS = 240
        val exceptionPattern = Regex(
            "(?<exception>(?:java|kotlin)\\.[A-Za-z0-9_.]+(?:Exception|Error))|(?<fatal>FATAL EXCEPTION)",
            RegexOption.IGNORE_CASE,
        )
        val tagPattern = Regex("[VDIWEAF]/(?<tag>[A-Za-z0-9_.-]+)")

        fun warningFromCode(code: String): RuntimeEvidenceWarning? = when (code) {
            "output_truncated" -> RuntimeEvidenceWarning.OUTPUT_TRUNCATED
            "timestamp_filter_unsupported" -> RuntimeEvidenceWarning.TIMESTAMP_FILTER_UNSUPPORTED
            "pid_filter_unsupported" -> RuntimeEvidenceWarning.PID_FILTER_UNSUPPORTED
            else -> null
        }

        fun failureFromCode(code: String?): RuntimeEvidenceFailureReason? = when (code) {
            null -> null
            "timeout" -> RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT
            "permission_denied" -> RuntimeEvidenceFailureReason.PERMISSION_DENIED
            "unsupported" -> RuntimeEvidenceFailureReason.COLLECTOR_UNSUPPORTED
            "process_not_running" -> RuntimeEvidenceFailureReason.PROCESS_NOT_RUNNING
            "package_not_available" -> RuntimeEvidenceFailureReason.PACKAGE_UNAVAILABLE
            else -> RuntimeEvidenceFailureReason.DEVICE_UNAVAILABLE
        }
    }
}
