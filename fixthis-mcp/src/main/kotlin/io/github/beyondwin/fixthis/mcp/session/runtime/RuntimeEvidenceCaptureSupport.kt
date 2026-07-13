package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceCapabilities
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceContext
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceKind
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceResult
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.tools.RuntimeEvidenceBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

internal class RuntimeEvidenceCollector(
    private val bridge: RuntimeEvidenceBridge,
    private val deadlineMillis: Long,
) {
    suspend fun collectBounded(
        packageName: String,
        preset: RuntimeEvidencePreset,
        screenCapturedAt: Long,
        collectionContext: RuntimeEvidenceCollectionContext,
        budget: RuntimeEvidenceDeadline,
    ): RuntimeEvidenceCollectorBatch {
        val requestedKinds = preset.runtimeEvidenceKinds()
        val completed = ConcurrentHashMap<CliRuntimeEvidenceKind, CliRuntimeEvidenceResult>()
        requestedKinds.mapNotNull {
            syntheticRuntimeEvidenceResult(it, collectionContext.context, collectionContext.capabilities)
        }.forEach { completed[it.kind] = it }
        val runnable = requestedKinds.filterNot(completed::containsKey)
        val collectorBudget = (budget.remainingMillis() - endContextReserve()).coerceAtLeast(1L)
        val completedWithinDeadline = withTimeoutOrNull(collectorBudget) {
            supervisorScope {
                runnable.map { kind ->
                    async {
                        val result = RuntimeEvidenceCaptureRuntime.collect {
                            collectWithRetry(packageName, kind, screenCapturedAt)
                        }
                        completed[kind] = result
                    }
                }.awaitAll()
            }
            true
        } ?: false
        return RuntimeEvidenceCollectorBatch(
            requestedKinds.map { kind -> completed[kind] ?: runtimeEvidenceTimeoutResult(kind) },
            timedOut = !completedWithinDeadline,
        )
    }

    suspend fun capabilitiesOrEmpty(
        packageName: String,
        budget: RuntimeEvidenceDeadline,
    ): CliRuntimeEvidenceCapabilities = try {
        withTimeoutOrNull(budget.remainingMillis().coerceAtLeast(1L)) {
            runInterruptible(Dispatchers.IO) { bridge.capabilities(packageName) }
        } ?: CliRuntimeEvidenceCapabilities(false, emptySet())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        CliRuntimeEvidenceCapabilities(false, emptySet())
    }

    suspend fun endContextWithinReserve(
        packageName: String,
        budget: RuntimeEvidenceDeadline,
    ): CliRuntimeEvidenceContext? {
        val remaining = budget.remainingMillis()
        return if (remaining <= 0) {
            null
        } else {
            withTimeoutOrNull(minOf(endContextReserve(), remaining)) { bridge.context(packageName) }
        }
    }

    private suspend fun collectWithRetry(
        packageName: String,
        kind: CliRuntimeEvidenceKind,
        screenCapturedAt: Long,
    ): CliRuntimeEvidenceResult {
        val first = bridge.collect(packageName, kind, screenCapturedAt)
        return if (first.isTransientRuntimeEvidenceFailure()) {
            bridge.collect(packageName, kind, screenCapturedAt)
        } else {
            first
        }
    }

    private fun endContextReserve(): Long = (deadlineMillis / END_CONTEXT_RESERVE_DIVISOR)
        .coerceIn(1L, END_CONTEXT_RESERVE_MILLIS)
}

internal data class RuntimeEvidencePayload(
    val captureId: String,
    val screenCapturedAt: Long,
    val captureStartedAt: Long,
    val proximity: RuntimeEvidenceProximity,
    val batch: RuntimeEvidenceCollectorBatch,
    val summaries: List<RuntimeEvidenceSummary>,
    val warnings: MutableSet<RuntimeEvidenceWarning>,
    val artifacts: List<RuntimeEvidenceArtifactInput>,
)

internal data class RuntimeEvidenceCollectorBatch(
    val results: List<CliRuntimeEvidenceResult>,
    val timedOut: Boolean,
)

internal data class RuntimeEvidenceCollectionContext(
    val context: CliRuntimeEvidenceContext,
    val capabilities: CliRuntimeEvidenceCapabilities,
)

internal data class RuntimeEvidenceContextDrift(
    val invalid: Boolean,
    val fingerprintChanged: Boolean = false,
    val warnings: Set<RuntimeEvidenceWarning> = emptySet(),
)

internal data class RuntimeEvidenceAttachmentContext(
    val session: SessionDto,
    val request: RuntimeEvidenceCaptureRequest,
    val startContext: CliRuntimeEvidenceContext,
    val drift: RuntimeEvidenceContextDrift,
    val completedAt: Long,
)

internal sealed interface RuntimeEvidenceCommitOutcome {
    data class Succeeded(val bundle: CommittedRuntimeEvidenceBundle) : RuntimeEvidenceCommitOutcome
    data class Failed(val reason: RuntimeEvidenceFailureReason) : RuntimeEvidenceCommitOutcome
}

internal class RuntimeEvidenceDeadline(timeoutMillis: Long) {
    private val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND

    fun remainingMillis(): Long = ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal suspend fun runtimeEvidenceContextOrNull(
    bridge: RuntimeEvidenceBridge,
    packageName: String,
    budget: RuntimeEvidenceDeadline,
): CliRuntimeEvidenceContext? = try {
    withTimeoutOrNull(budget.remainingMillis().coerceAtLeast(1L)) { bridge.context(packageName) }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: RuntimeException) {
    null
}

internal fun stableRuntimeEvidenceSessionOrNull(
    store: FeedbackSessionStore,
    request: RuntimeEvidenceCaptureRequest,
): SessionDto? = runCatching {
    store.getSession(request.sessionId)
}.getOrNull()?.takeIf { session ->
    val ids = request.itemIds.toSet()
    session.status != SessionStatusDto.CLOSED &&
        session.screens.any { it.screenId == request.screenId } &&
        ids.size == request.itemIds.size &&
        session.items.filter { it.itemId in ids && it.screenId == request.screenId }.map { it.itemId }.toSet() == ids
}

internal fun classifyRuntimeEvidenceDrift(
    store: FeedbackSessionStore,
    start: CliRuntimeEvidenceContext,
    end: CliRuntimeEvidenceContext,
    session: SessionDto,
    request: RuntimeEvidenceCaptureRequest,
): RuntimeEvidenceContextDrift {
    val latest = stableRuntimeEvidenceSessionOrNull(store, request)
    val invalid = start.deviceSerial != end.deviceSerial ||
        start.installEpochMillis != end.installEpochMillis ||
        start.packageName != end.packageName ||
        !end.packageAvailable ||
        latest == null
    val fingerprintChanged = start.currentScreenFingerprint != end.currentScreenFingerprint ||
        session.screens.firstOrNull { it.screenId == request.screenId }?.fingerprint
            ?.let { it != start.currentScreenFingerprint } == true
    val warnings = buildSet {
        if (start.pid != end.pid) add(RuntimeEvidenceWarning.PROCESS_RESTARTED)
        if (fingerprintChanged) add(RuntimeEvidenceWarning.CONTEXT_CHANGED)
    }
    return RuntimeEvidenceContextDrift(invalid, fingerprintChanged, warnings)
}

internal const val DEFAULT_DEADLINE_MILLIS = 2_500L
private const val END_CONTEXT_RESERVE_MILLIS = 500L
private const val END_CONTEXT_RESERVE_DIVISOR = 5L
