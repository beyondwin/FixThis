package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceContext
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceResult
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto

internal data class RuntimeEvidenceAssembler(
    val request: RuntimeEvidenceCaptureRequest,
    val session: SessionDto,
    val startContext: CliRuntimeEvidenceContext,
    val budget: RuntimeEvidenceDeadline,
    val captureId: String,
    val captureStartedAt: Long,
)

internal suspend fun collectRuntimeEvidencePayload(
    input: RuntimeEvidenceAssembler,
    collector: RuntimeEvidenceCollector,
    summarizer: RuntimeEvidenceSummarizer,
    redactor: RuntimeEvidenceRedactor,
): RuntimeEvidencePayload {
    val screenCapturedAt = input.session.screens
        .first { it.screenId == input.request.screenId }
        .capturedAtEpochMillis
    val proximity = runtimeEvidenceProximity(input.captureStartedAt - screenCapturedAt)
    val capabilities = collector.capabilitiesOrEmpty(input.session.packageName, input.budget)
    val batch = collector.collectBounded(
        input.session.packageName,
        input.request.preset,
        screenCapturedAt,
        RuntimeEvidenceCollectionContext(input.startContext, capabilities),
        input.budget,
    )
    val summaries = batch.results.map { summarizer.summarize(it, screenCapturedAt) }
    val warnings = linkedSetOf<RuntimeEvidenceWarning>().apply {
        summaries.flatMapTo(this) { it.warnings }
        if (proximity == RuntimeEvidenceProximity.STALE) add(RuntimeEvidenceWarning.STALE_WINDOW)
        if (input.captureStartedAt < screenCapturedAt) add(RuntimeEvidenceWarning.CONTEXT_CHANGED)
    }
    return RuntimeEvidencePayload(
        input.captureId,
        screenCapturedAt,
        input.captureStartedAt,
        proximity,
        batch,
        summaries,
        warnings,
        runtimeEvidenceArtifactInputs(batch.results, warnings, redactor),
    )
}

internal fun createRuntimeEvidenceAttachments(
    captured: RuntimeEvidencePayload,
    committed: CommittedRuntimeEvidenceBundle,
    context: RuntimeEvidenceAttachmentContext,
    idGenerator: () -> String,
): List<RuntimeEvidenceAttachment> = captured.summaries.map { summary ->
    RuntimeEvidenceAttachment(
        evidenceId = idGenerator(),
        type = summary.type,
        capturedAtEpochMillis = context.completedAt,
        deviceSerial = context.startContext.deviceSerial,
        packageName = context.session.packageName,
        summary = summary.summary,
        artifactPath = committed.relativeFiles[summary.type],
        captureCommand = summary.type.allowlistedRuntimeEvidenceCommand(),
        warnings = (summary.warnings + captured.warnings).distinct(),
        captureId = captured.captureId,
        status = adjustedRuntimeEvidenceStatus(summary.status, context.drift),
        trigger = context.request.trigger,
        screenCapturedAtEpochMillis = captured.screenCapturedAt,
        captureStartedAtEpochMillis = captured.captureStartedAt,
        captureCompletedAtEpochMillis = context.completedAt,
        proximity = effectiveRuntimeEvidenceProximity(captured.proximity, context.drift),
        failureReason = summary.failureReason,
    )
}

private fun runtimeEvidenceArtifactInputs(
    results: List<CliRuntimeEvidenceResult>,
    warnings: MutableSet<RuntimeEvidenceWarning>,
    redactor: RuntimeEvidenceRedactor,
): List<RuntimeEvidenceArtifactInput> = results.mapNotNull { result ->
    if (result.output.isBlank()) return@mapNotNull null
    val redacted = redactor.redact(result.output)
    if (redacted.redacted) warnings += RuntimeEvidenceWarning.REDACTION_APPLIED
    RuntimeEvidenceArtifactInput(result.kind.toRuntimeEvidenceType(), result.kind.runtimeEvidenceFileName(), redacted.text)
}

private fun adjustedRuntimeEvidenceStatus(
    status: RuntimeEvidenceStatus,
    drift: RuntimeEvidenceContextDrift,
): RuntimeEvidenceStatus = if (status == RuntimeEvidenceStatus.COMPLETE && drift.warnings.isNotEmpty()) {
    RuntimeEvidenceStatus.PARTIAL
} else {
    status
}

private fun effectiveRuntimeEvidenceProximity(
    proximity: RuntimeEvidenceProximity,
    drift: RuntimeEvidenceContextDrift,
): RuntimeEvidenceProximity = if (drift.fingerprintChanged && proximity == RuntimeEvidenceProximity.NEAR) {
    RuntimeEvidenceProximity.DELAYED
} else {
    proximity
}

private fun runtimeEvidenceProximity(delta: Long): RuntimeEvidenceProximity = when {
    delta < 0 -> RuntimeEvidenceProximity.STALE
    delta <= NEAR_MILLIS -> RuntimeEvidenceProximity.NEAR
    delta <= DELAYED_MILLIS -> RuntimeEvidenceProximity.DELAYED
    else -> RuntimeEvidenceProximity.STALE
}

private const val NEAR_MILLIS = 3_000L
private const val DELAYED_MILLIS = 15_000L
