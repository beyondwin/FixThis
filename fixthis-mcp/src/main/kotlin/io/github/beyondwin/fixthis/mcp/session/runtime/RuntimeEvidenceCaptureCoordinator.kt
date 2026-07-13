package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceContext
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.tools.RuntimeEvidenceBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
enum class RuntimeEvidencePreset {
    @SerialName("baseline")
    BASELINE,

    @SerialName("logs")
    LOGS,

    @SerialName("memory")
    MEMORY,

    @SerialName("performance")
    PERFORMANCE,
}

data class RuntimeEvidenceCaptureRequest(
    val sessionId: String,
    val itemIds: List<String>,
    val screenId: String,
    val preset: RuntimeEvidencePreset,
    val trigger: RuntimeEvidenceTrigger,
)

@Serializable
data class RuntimeEvidenceCaptureResult(
    val attempted: Boolean,
    val captureId: String? = null,
    val status: RuntimeEvidenceStatus? = null,
    val attachmentIds: List<String> = emptyList(),
    val linkedItemIds: List<String> = emptyList(),
    val artifactDirectory: String? = null,
    val warnings: List<RuntimeEvidenceWarning> = emptyList(),
    val failureReason: RuntimeEvidenceFailureReason? = null,
    val skippedReason: String? = null,
) {
    companion object {
        fun skipped(reason: String) = RuntimeEvidenceCaptureResult(attempted = false, skippedReason = reason)
    }
}

internal fun interface RuntimeEvidenceLinker {
    fun link(
        sessionId: String,
        expectedScreenId: String,
        itemIds: List<String>,
        attachments: List<RuntimeEvidenceAttachment>,
        aggregateStatus: RuntimeEvidenceStatus,
    ): SessionDto
}

internal data class RuntimeEvidenceCaptureDependencies(
    val redactor: RuntimeEvidenceRedactor,
    val summarizer: RuntimeEvidenceSummarizer,
    val idGenerator: () -> String,
    val clock: () -> Long = System::currentTimeMillis,
    val deadlineMillis: Long = DEFAULT_DEADLINE_MILLIS,
    val linker: RuntimeEvidenceLinker? = null,
)

@Suppress("TooManyFunctions")
internal class RuntimeEvidenceCaptureCoordinator(
    private val bridge: RuntimeEvidenceBridge,
    private val store: FeedbackSessionStore,
    projectRoot: File,
    private val artifactStore: RuntimeEvidenceArtifactStore,
    dependencies: RuntimeEvidenceCaptureDependencies,
) {
    private val projectRoot = projectRoot.canonicalPath
    private val redactor = dependencies.redactor
    private val summarizer = dependencies.summarizer
    private val idGenerator = dependencies.idGenerator
    private val clock = dependencies.clock
    private val deadlineMillis = dependencies.deadlineMillis
    private val linker = dependencies.linker ?: RuntimeEvidenceLinker(store::attachRuntimeEvidence)
    private val collector = RuntimeEvidenceCollector(bridge, deadlineMillis)

    suspend fun collect(request: RuntimeEvidenceCaptureRequest): RuntimeEvidenceCaptureResult {
        require(request.itemIds.isNotEmpty()) { "itemIds must not be empty" }
        val budget = RuntimeEvidenceDeadline(deadlineMillis)
        val session = stableRuntimeEvidenceSessionOrNull(store, request)
        return if (session == null) contextFailure() else collectForSession(request, session, budget)
    }

    private suspend fun collectForSession(
        request: RuntimeEvidenceCaptureRequest,
        session: SessionDto,
        budget: RuntimeEvidenceDeadline,
    ): RuntimeEvidenceCaptureResult {
        val startContext = runtimeEvidenceContextOrNull(bridge, session.packageName, budget)
        return when {
            startContext == null -> deviceFailure()
            !startContext.packageAvailable -> packageFailure()
            request.trigger == RuntimeEvidenceTrigger.HANDOFF_AUTO -> deduplicatedCollect(
                request,
                session,
                startContext,
                budget,
            )
            else -> manualCollect(request, session, startContext, budget)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun deduplicatedCollect(
        request: RuntimeEvidenceCaptureRequest,
        session: SessionDto,
        startContext: CliRuntimeEvidenceContext,
        budget: RuntimeEvidenceDeadline,
    ): RuntimeEvidenceCaptureResult {
        val key = CaptureKey(
            projectRoot = projectRoot,
            sessionId = request.sessionId,
            screenId = request.screenId,
            preset = request.preset,
            deviceSerial = startContext.deviceSerial,
            installEpochMillis = startContext.installEpochMillis,
        )
        val lease = RuntimeEvidenceCaptureRuntime.acquire(key) {
            prepareCapture(request, session, startContext, budget)
        }
        var prepared: RuntimeEvidencePreparedCapture? = null
        var disposition = RuntimeEvidenceLinkDisposition.NONE
        return try {
            prepared = withTimeoutOrNull(budget.remainingMillis().coerceAtLeast(1L)) { lease.await() }
                ?: return artifactFailure("capture-timeout", RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT)
            val completion = completePrepared(request, prepared, deleteExactRejection = false, budget = budget)
            disposition = completion.disposition
            completion.result
        } catch (cancelled: CancellationException) {
            disposition = RuntimeEvidenceLinkDisposition.PRESERVE
            throw cancelled
        } finally {
            val committed = prepared as? RuntimeEvidencePreparedCapture.Committed
            lease.release(disposition) {
                committed?.bundle?.let { artifactStore.deleteBundle(request.sessionId, it.captureId) }
            }
        }
    }

    private suspend fun manualCollect(
        request: RuntimeEvidenceCaptureRequest,
        session: SessionDto,
        startContext: CliRuntimeEvidenceContext,
        budget: RuntimeEvidenceDeadline,
    ): RuntimeEvidenceCaptureResult {
        val prepared = prepareCapture(request, session, startContext, budget)
        return completePrepared(request, prepared, deleteExactRejection = true, budget = budget).result
    }

    private suspend fun prepareCapture(
        request: RuntimeEvidenceCaptureRequest,
        session: SessionDto,
        startContext: CliRuntimeEvidenceContext,
        budget: RuntimeEvidenceDeadline,
    ): RuntimeEvidencePreparedCapture {
        val captured = collectRuntimeEvidencePayload(
            input = RuntimeEvidenceAssembler(
                request = request,
                session = session,
                startContext = startContext,
                budget = budget,
                captureId = idGenerator(),
                captureStartedAt = clock(),
            ),
            collector = collector,
            summarizer = summarizer,
            redactor = redactor,
        )
        return when (
            val endContext = collector.endContextWithinReserve(
                session.packageName,
                startContext.deviceSerial,
                budget,
            )
        ) {
            RuntimeEvidenceEndContextOutcome.TimedOut -> RuntimeEvidencePreparedCapture.Terminal(
                artifactFailure(captured.captureId, RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT),
            )
            RuntimeEvidenceEndContextOutcome.Unavailable -> RuntimeEvidencePreparedCapture.Terminal(
                deviceFailure(captured.captureId),
            )
            is RuntimeEvidenceEndContextOutcome.Available -> finishCapture(
                request,
                session,
                startContext,
                captured,
                endContext.context,
                budget,
            )
        }
    }

    @Suppress("LongParameterList")
    private suspend fun finishCapture(
        request: RuntimeEvidenceCaptureRequest,
        session: SessionDto,
        startContext: CliRuntimeEvidenceContext,
        captured: RuntimeEvidencePayload,
        endContext: CliRuntimeEvidenceContext,
        budget: RuntimeEvidenceDeadline,
    ): RuntimeEvidencePreparedCapture {
        val drift = classifyRuntimeEvidenceDrift(store, startContext, endContext, session, request)
        return when {
            drift.invalid -> RuntimeEvidencePreparedCapture.Terminal(contextFailure(captured.captureId))
            captured.artifacts.isEmpty() -> RuntimeEvidencePreparedCapture.Terminal(
                runtimeEvidenceResultWithoutArtifacts(captured),
            )
            else -> commitPrepared(request, session, startContext, captured, drift, budget)
        }
    }

    @Suppress("LongParameterList", "ReturnCount")
    private suspend fun commitPrepared(
        request: RuntimeEvidenceCaptureRequest,
        session: SessionDto,
        startContext: CliRuntimeEvidenceContext,
        captured: RuntimeEvidencePayload,
        drift: RuntimeEvidenceContextDrift,
        budget: RuntimeEvidenceDeadline,
    ): RuntimeEvidencePreparedCapture {
        captured.warnings += drift.warnings
        val remaining = budget.remainingMillis()
        if (remaining <= 0) {
            return RuntimeEvidencePreparedCapture.Terminal(
                artifactFailure(captured.captureId, RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT),
            )
        }
        val outcome = withTimeoutOrNull(remaining) {
            runInterruptible(Dispatchers.IO) {
                commitRuntimeEvidence(artifactStore, request.sessionId, captured)
            }
        } ?: return RuntimeEvidencePreparedCapture.Terminal(
            artifactFailure(captured.captureId, RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT),
        )
        return when (outcome) {
            is RuntimeEvidenceCommitOutcome.Failed -> RuntimeEvidencePreparedCapture.Terminal(
                artifactFailure(captured.captureId, outcome.reason),
            )
            is RuntimeEvidenceCommitOutcome.Succeeded -> {
                val attachments = createRuntimeEvidenceAttachments(
                    captured,
                    outcome.bundle,
                    RuntimeEvidenceAttachmentContext(session, request, startContext, drift, clock()),
                    idGenerator,
                )
                RuntimeEvidencePreparedCapture.Committed(
                    outcome.bundle,
                    attachments,
                    aggregateRuntimeEvidenceStatus(captured),
                    captured.warnings,
                )
            }
        }
    }

    private suspend fun completePrepared(
        request: RuntimeEvidenceCaptureRequest,
        prepared: RuntimeEvidencePreparedCapture,
        deleteExactRejection: Boolean,
        budget: RuntimeEvidenceDeadline,
    ): RuntimeEvidenceLinkCompletion = when (prepared) {
        is RuntimeEvidencePreparedCapture.Terminal -> RuntimeEvidenceLinkCompletion(
            prepared.result,
            RuntimeEvidenceLinkDisposition.NONE,
        )
        is RuntimeEvidencePreparedCapture.Committed -> linkCommitted(request, prepared, deleteExactRejection, budget)
    }

    @Suppress("LongParameterList", "ReturnCount", "TooGenericExceptionCaught")
    private suspend fun linkCommitted(
        request: RuntimeEvidenceCaptureRequest,
        prepared: RuntimeEvidencePreparedCapture.Committed,
        deleteExactRejection: Boolean,
        budget: RuntimeEvidenceDeadline,
    ): RuntimeEvidenceLinkCompletion {
        val remaining = budget.remainingMillis()
        if (remaining <= 0) return timedOutLink(prepared)
        val linked = try {
            val session = withTimeoutOrNull(remaining) {
                runInterruptible(Dispatchers.IO) {
                    linker.link(
                        request.sessionId,
                        request.screenId,
                        request.itemIds,
                        prepared.attachments,
                        prepared.aggregate,
                    )
                }
            } ?: return timedOutLink(prepared)
            Result.success(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        return linked.fold(
            onSuccess = {
                RuntimeEvidenceLinkCompletion(
                    RuntimeEvidenceCaptureResult(
                        attempted = true,
                        captureId = prepared.bundle.captureId,
                        status = prepared.aggregate,
                        attachmentIds = prepared.attachments.map { it.evidenceId },
                        linkedItemIds = request.itemIds,
                        artifactDirectory = prepared.bundle.relativeDirectory,
                        warnings = prepared.warnings.toList(),
                        failureReason = prepared.attachments.mapNotNull { it.failureReason }.firstOrNull(),
                    ),
                    RuntimeEvidenceLinkDisposition.SUCCESS,
                )
            },
            onFailure = { failure -> linkFailureResult(request, prepared, failure, deleteExactRejection) },
        )
    }

    private fun timedOutLink(prepared: RuntimeEvidencePreparedCapture.Committed): RuntimeEvidenceLinkCompletion = RuntimeEvidenceLinkCompletion(
        artifactFailure(
            prepared.bundle.captureId,
            RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT,
            prepared.bundle,
        ),
        // A timed-out linker may have appended the session event before its
        // post-commit persistence/compaction work was interrupted. Preserve
        // the bundle unless a pre-append rejection proves it is unreferenced.
        RuntimeEvidenceLinkDisposition.PRESERVE,
    )

    private fun linkFailureResult(
        request: RuntimeEvidenceCaptureRequest,
        prepared: RuntimeEvidencePreparedCapture.Committed,
        failure: Throwable,
        deleteExactRejection: Boolean,
    ): RuntimeEvidenceLinkCompletion = when {
        failure is CancellationException -> throw failure
        failure.isPreAppendRuntimeEvidenceContextChange() -> {
            if (deleteExactRejection) artifactStore.deleteBundle(request.sessionId, prepared.bundle.captureId)
            RuntimeEvidenceLinkCompletion(
                contextFailure(prepared.bundle.captureId),
                RuntimeEvidenceLinkDisposition.EXACT_REJECTION,
            )
        }
        else -> RuntimeEvidenceLinkCompletion(
            artifactFailure(
                prepared.bundle.captureId,
                RuntimeEvidenceFailureReason.ARTIFACT_WRITE_FAILED,
                prepared.bundle,
            ),
            RuntimeEvidenceLinkDisposition.PRESERVE,
        )
    }
}
