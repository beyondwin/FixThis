package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceContext
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.tools.RuntimeEvidenceBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
            else -> collectOnce(request, session, startContext, budget)
        }
    }

    private suspend fun deduplicatedCollect(
        request: RuntimeEvidenceCaptureRequest,
        session: SessionDto,
        startContext: CliRuntimeEvidenceContext,
        budget: RuntimeEvidenceDeadline,
    ): RuntimeEvidenceCaptureResult = RuntimeEvidenceCaptureRuntime.deduplicate(
        CaptureKey(
            projectRoot = projectRoot,
            sessionId = request.sessionId,
            screenId = request.screenId,
            preset = request.preset,
            installEpochMillis = startContext.installEpochMillis,
        ),
    ) {
        collectOnce(request, session, startContext, budget)
    }

    private suspend fun collectOnce(
        request: RuntimeEvidenceCaptureRequest,
        session: SessionDto,
        startContext: CliRuntimeEvidenceContext,
        budget: RuntimeEvidenceDeadline,
    ): RuntimeEvidenceCaptureResult {
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
        val endContext = collector.endContextWithinReserve(session.packageName, budget)
        val drift = endContext?.let { classifyRuntimeEvidenceDrift(store, startContext, it, session, request) }
        return when {
            endContext == null -> artifactFailure(captured.captureId, RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT)
            drift?.invalid != false -> contextFailure(captured.captureId)
            captured.artifacts.isEmpty() -> runtimeEvidenceResultWithoutArtifacts(captured)
            else -> commitAndLink(request, session, startContext, captured, drift)
        }
    }

    private fun commitAndLink(
        request: RuntimeEvidenceCaptureRequest,
        session: SessionDto,
        startContext: CliRuntimeEvidenceContext,
        captured: RuntimeEvidencePayload,
        drift: RuntimeEvidenceContextDrift,
    ): RuntimeEvidenceCaptureResult {
        captured.warnings += drift.warnings
        return when (val outcome = commit(request.sessionId, captured)) {
            is RuntimeEvidenceCommitOutcome.Failed -> artifactFailure(captured.captureId, outcome.reason)
            is RuntimeEvidenceCommitOutcome.Succeeded -> {
                val attachmentContext = RuntimeEvidenceAttachmentContext(
                    session = session,
                    request = request,
                    startContext = startContext,
                    drift = drift,
                    completedAt = clock(),
                )
                val attachments = createRuntimeEvidenceAttachments(
                    captured,
                    outcome.bundle,
                    attachmentContext,
                    idGenerator,
                )
                val aggregate = aggregateRuntimeEvidenceStatus(captured)
                linkCommitted(request, outcome.bundle, attachments, aggregate, captured.warnings)
            }
        }
    }

    private fun commit(
        sessionId: String,
        captured: RuntimeEvidencePayload,
    ): RuntimeEvidenceCommitOutcome = try {
        RuntimeEvidenceCommitOutcome.Succeeded(
            artifactStore.commit(sessionId, captured.captureId, captured.artifacts),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeEvidenceArtifactQuotaException) {
        RuntimeEvidenceCommitOutcome.Failed(RuntimeEvidenceFailureReason.QUOTA_EXCEEDED)
    } catch (_: RuntimeException) {
        RuntimeEvidenceCommitOutcome.Failed(RuntimeEvidenceFailureReason.ARTIFACT_WRITE_FAILED)
    }

    private fun linkCommitted(
        request: RuntimeEvidenceCaptureRequest,
        committed: CommittedRuntimeEvidenceBundle,
        attachments: List<RuntimeEvidenceAttachment>,
        aggregate: RuntimeEvidenceStatus,
        warnings: Set<RuntimeEvidenceWarning>,
    ): RuntimeEvidenceCaptureResult {
        val linked = runCatching {
            linker.link(request.sessionId, request.screenId, request.itemIds, attachments, aggregate)
        }
        return linked.fold(
            onSuccess = {
                RuntimeEvidenceCaptureResult(
                    attempted = true,
                    captureId = committed.captureId,
                    status = aggregate,
                    attachmentIds = attachments.map { it.evidenceId },
                    linkedItemIds = request.itemIds,
                    artifactDirectory = committed.relativeDirectory,
                    warnings = warnings.toList(),
                    failureReason = attachments.mapNotNull { it.failureReason }.firstOrNull(),
                )
            },
            onFailure = { failure -> linkFailureResult(request, committed, failure) },
        )
    }

    private fun linkFailureResult(
        request: RuntimeEvidenceCaptureRequest,
        committed: CommittedRuntimeEvidenceBundle,
        failure: Throwable,
    ): RuntimeEvidenceCaptureResult = when {
        failure is CancellationException -> throw failure
        failure.isPreAppendRuntimeEvidenceContextChange() -> {
            artifactStore.deleteBundle(request.sessionId, committed.captureId)
            contextFailure(committed.captureId)
        }
        else -> artifactFailure(
            committed.captureId,
            RuntimeEvidenceFailureReason.ARTIFACT_WRITE_FAILED,
            committed,
        )
    }
}

internal data class CaptureKey(
    val projectRoot: String,
    val sessionId: String,
    val screenId: String,
    val preset: RuntimeEvidencePreset,
    val installEpochMillis: Long?,
)

internal object RuntimeEvidenceCaptureRuntime {
    private val semaphore = Semaphore(MAX_CONCURRENT_COLLECTORS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = ConcurrentHashMap<CaptureKey, Deferred<RuntimeEvidenceCaptureResult>>()

    suspend fun <T> collect(block: suspend () -> T): T = semaphore.withPermit { block() }

    suspend fun deduplicate(
        key: CaptureKey,
        block: suspend () -> RuntimeEvidenceCaptureResult,
    ): RuntimeEvidenceCaptureResult {
        val candidate = scope.async(start = CoroutineStart.LAZY) { block() }
        val existing = inFlight.putIfAbsent(key, candidate)
        val selected = existing ?: candidate.also { deferred ->
            deferred.invokeOnCompletion { inFlight.remove(key, deferred) }
            deferred.start()
        }
        if (existing != null) candidate.cancel()
        return selected.await()
    }
}

private const val MAX_CONCURRENT_COLLECTORS = 2
