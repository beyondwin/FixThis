package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionException
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.RUNTIME_EVIDENCE_CONTEXT_CHANGED_PREFIX

internal fun runtimeEvidenceResultWithoutArtifacts(captured: RuntimeEvidencePayload): RuntimeEvidenceCaptureResult = RuntimeEvidenceCaptureResult(
    attempted = true,
    captureId = captured.captureId,
    status = aggregateRuntimeEvidenceStatus(captured),
    warnings = captured.warnings.toList(),
    failureReason = captured.summaries.mapNotNull { it.failureReason }.firstOrNull()
        ?: if (captured.batch.timedOut) RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT else null,
)

internal fun aggregateRuntimeEvidenceStatus(captured: RuntimeEvidencePayload): RuntimeEvidenceStatus {
    val statuses = captured.summaries.map { it.status }
    return when {
        statuses.all { it == RuntimeEvidenceStatus.UNSUPPORTED } -> RuntimeEvidenceStatus.UNSUPPORTED
        statuses.all { it == RuntimeEvidenceStatus.FAILED } -> RuntimeEvidenceStatus.FAILED
        captured.batch.timedOut ||
            statuses.any { it != RuntimeEvidenceStatus.COMPLETE } ||
            RuntimeEvidenceWarning.PROCESS_RESTARTED in captured.warnings ||
            RuntimeEvidenceWarning.CONTEXT_CHANGED in captured.warnings -> RuntimeEvidenceStatus.PARTIAL
        else -> RuntimeEvidenceStatus.COMPLETE
    }
}

internal fun contextFailure(captureId: String? = null) = RuntimeEvidenceCaptureResult(
    attempted = true,
    captureId = captureId,
    status = RuntimeEvidenceStatus.FAILED,
    failureReason = RuntimeEvidenceFailureReason.CONTEXT_CHANGED,
    warnings = listOf(RuntimeEvidenceWarning.CONTEXT_CHANGED),
)

internal fun deviceFailure() = RuntimeEvidenceCaptureResult(
    attempted = true,
    status = RuntimeEvidenceStatus.FAILED,
    failureReason = RuntimeEvidenceFailureReason.DEVICE_UNAVAILABLE,
)

internal fun packageFailure() = RuntimeEvidenceCaptureResult(
    attempted = true,
    status = RuntimeEvidenceStatus.FAILED,
    failureReason = RuntimeEvidenceFailureReason.PACKAGE_UNAVAILABLE,
)

internal fun artifactFailure(
    captureId: String,
    reason: RuntimeEvidenceFailureReason,
    committed: CommittedRuntimeEvidenceBundle? = null,
) = RuntimeEvidenceCaptureResult(
    attempted = true,
    captureId = captureId,
    status = RuntimeEvidenceStatus.FAILED,
    artifactDirectory = committed?.relativeDirectory,
    failureReason = reason,
)

internal fun Throwable.isPreAppendRuntimeEvidenceContextChange(): Boolean = this is FeedbackSessionException && message.orEmpty().startsWith(RUNTIME_EVIDENCE_CONTEXT_CHANGED_PREFIX)
