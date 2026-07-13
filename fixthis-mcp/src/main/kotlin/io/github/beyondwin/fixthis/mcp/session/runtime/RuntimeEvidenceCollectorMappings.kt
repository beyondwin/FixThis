package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceCapabilities
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceContext
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceKind
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceResult
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceStatus

internal fun syntheticRuntimeEvidenceResult(
    kind: CliRuntimeEvidenceKind,
    context: CliRuntimeEvidenceContext,
    capabilities: CliRuntimeEvidenceCapabilities,
): CliRuntimeEvidenceResult? {
    val failureCode = when {
        kind in setOf(CliRuntimeEvidenceKind.MEMORY_SUMMARY, CliRuntimeEvidenceKind.FRAME_SUMMARY) && context.pid == null ->
            "process_not_running"
        kind !in capabilities.supportedCollectors -> "unsupported"
        else -> return null
    }
    val status = if (failureCode == "unsupported") CliRuntimeEvidenceStatus.UNSUPPORTED else CliRuntimeEvidenceStatus.FAILED
    return emptyRuntimeEvidenceResult(kind, status, failureCode)
}

internal fun RuntimeEvidencePreset.runtimeEvidenceKinds(): List<CliRuntimeEvidenceKind> = when (this) {
    RuntimeEvidencePreset.BASELINE -> listOf(
        CliRuntimeEvidenceKind.LOGCAT_WINDOW,
        CliRuntimeEvidenceKind.MEMORY_SUMMARY,
        CliRuntimeEvidenceKind.FRAME_SUMMARY,
    )
    RuntimeEvidencePreset.LOGS -> listOf(CliRuntimeEvidenceKind.LOGCAT_WINDOW)
    RuntimeEvidencePreset.MEMORY -> listOf(CliRuntimeEvidenceKind.MEMORY_SUMMARY)
    RuntimeEvidencePreset.PERFORMANCE -> listOf(CliRuntimeEvidenceKind.FRAME_SUMMARY)
}

internal fun CliRuntimeEvidenceKind.toRuntimeEvidenceType(): RuntimeEvidenceType = when (this) {
    CliRuntimeEvidenceKind.LOGCAT_WINDOW -> RuntimeEvidenceType.LOGCAT_WINDOW
    CliRuntimeEvidenceKind.MEMORY_SUMMARY -> RuntimeEvidenceType.MEMORY_SUMMARY
    CliRuntimeEvidenceKind.FRAME_SUMMARY -> RuntimeEvidenceType.FRAME_SUMMARY
    CliRuntimeEvidenceKind.CONTEXT -> RuntimeEvidenceType.TRACE_ARTIFACT
}

internal fun CliRuntimeEvidenceKind.runtimeEvidenceFileName(): String = when (this) {
    CliRuntimeEvidenceKind.LOGCAT_WINDOW -> "logcat.txt"
    CliRuntimeEvidenceKind.MEMORY_SUMMARY -> "memory-summary.txt"
    CliRuntimeEvidenceKind.FRAME_SUMMARY -> "frame-summary.txt"
    CliRuntimeEvidenceKind.CONTEXT -> "context.txt"
}

internal fun RuntimeEvidenceType.allowlistedRuntimeEvidenceCommand(): String = when (this) {
    RuntimeEvidenceType.LOGCAT_WINDOW -> "adb:logcat_window"
    RuntimeEvidenceType.MEMORY_SUMMARY -> "adb:memory_summary"
    RuntimeEvidenceType.FRAME_SUMMARY -> "adb:frame_summary"
    RuntimeEvidenceType.TRACE_ARTIFACT -> "adb:trace_artifact"
}

internal fun CliRuntimeEvidenceResult.isTransientRuntimeEvidenceFailure(): Boolean = status == CliRuntimeEvidenceStatus.FAILED &&
    failureCode in setOf("adb_command_failed", "process_not_running", "device_unavailable", "timeout")

internal fun runtimeEvidenceTimeoutResult(kind: CliRuntimeEvidenceKind) = emptyRuntimeEvidenceResult(
    kind,
    CliRuntimeEvidenceStatus.FAILED,
    "timeout",
)

private fun emptyRuntimeEvidenceResult(
    kind: CliRuntimeEvidenceKind,
    status: CliRuntimeEvidenceStatus,
    failureCode: String,
) = CliRuntimeEvidenceResult(
    kind = kind,
    status = status,
    startedAtEpochMillis = 0,
    completedAtEpochMillis = 0,
    command = emptyList(),
    output = "",
    failureCode = failureCode,
)
