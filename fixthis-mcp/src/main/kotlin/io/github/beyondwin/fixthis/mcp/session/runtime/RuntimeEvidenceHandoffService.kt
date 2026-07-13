package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

data class SendDraftToAgentWithRuntimeEvidenceResult(
    val session: SessionDto,
    val prompt: String,
    val runtimeEvidence: RuntimeEvidenceCaptureResult,
)

internal class RuntimeEvidenceHandoffService(
    private val readSession: (String) -> SessionDto,
    private val collect: suspend (RuntimeEvidenceCaptureRequest) -> RuntimeEvidenceCaptureResult,
    private val render: (SessionDto, List<String>) -> String,
    private val markSent: (String, String, List<String>) -> SessionDto,
) {
    private val inFlight = ConcurrentHashMap<HandoffKey, CompletableDeferred<SendDraftToAgentWithRuntimeEvidenceResult>>()

    // Every owner failure must complete the shared deferred so concurrent waiters cannot hang.
    @Suppress("TooGenericExceptionCaught")
    suspend fun sendDraftToAgentWithRuntimeEvidence(
        sessionId: String,
        itemIds: List<String>,
    ): SendDraftToAgentWithRuntimeEvidenceResult {
        require(itemIds.isNotEmpty()) { "itemIds must not be empty" }
        val uniqueItemIds = itemIds.distinct()
        val key = HandoffKey(sessionId, uniqueItemIds.sorted())
        val owned = CompletableDeferred<SendDraftToAgentWithRuntimeEvidenceResult>()
        val shared = inFlight.putIfAbsent(key, owned)
        if (shared != null) return shared.await()

        try {
            val result = execute(sessionId, uniqueItemIds)
            owned.complete(result)
            return result
        } catch (cancelled: CancellationException) {
            owned.cancel(cancelled)
            throw cancelled
        } catch (failure: Throwable) {
            owned.completeExceptionally(failure)
            throw failure
        } finally {
            inFlight.remove(key, owned)
        }
    }

    private suspend fun execute(
        sessionId: String,
        itemIds: List<String>,
    ): SendDraftToAgentWithRuntimeEvidenceResult {
        val before = readSession(sessionId)
        val runtimeEvidence = when (before.runtimeEvidencePolicy) {
            RuntimeEvidencePolicy.AUTO_ON_HANDOFF -> collect(autoRequest(before, itemIds))
            RuntimeEvidencePolicy.MANUAL -> RuntimeEvidenceCaptureResult.skipped("manual")
            RuntimeEvidencePolicy.OFF -> RuntimeEvidenceCaptureResult.skipped("off")
        }
        val finalSession = readSession(sessionId)
        val prompt = render(finalSession, itemIds).trimEnd() + runtimeEvidenceAttemptBlock(runtimeEvidence)
        val sent = markSent(sessionId, prompt, itemIds)
        return SendDraftToAgentWithRuntimeEvidenceResult(
            session = sent,
            prompt = prompt,
            runtimeEvidence = runtimeEvidence,
        )
    }

    private fun autoRequest(session: SessionDto, itemIds: List<String>): RuntimeEvidenceCaptureRequest {
        val targetIds = itemIds.toSet()
        val targetItems = session.items.filter { it.itemId in targetIds }
        require(targetItems.map { it.itemId }.toSet() == targetIds) { "Feedback item not found" }
        val screenIds = targetItems.map { it.screenId }.distinct()
        require(screenIds.size == 1) { "Automatic runtime evidence requires items from one screen" }
        return RuntimeEvidenceCaptureRequest(
            sessionId = session.sessionId,
            itemIds = itemIds,
            screenId = screenIds.single(),
            preset = RuntimeEvidencePreset.BASELINE,
            trigger = RuntimeEvidenceTrigger.HANDOFF_AUTO,
        )
    }

    private fun runtimeEvidenceAttemptBlock(result: RuntimeEvidenceCaptureResult): String = buildString {
        append("\n\nruntimeEvidenceAttempt:\n")
        append("  attempted=").append(result.attempted).append('\n')
        append("  status=").append(result.status?.name?.lowercase() ?: "skipped").append('\n')
        result.failureReason?.let { append("  failure=").append(it.name.lowercase()).append('\n') }
        val skipped = when (result.skippedReason) {
            "manual" -> "manual"
            "off" -> "off"
            null -> null
            else -> "skipped"
        }
        skipped?.let { append("  reason=").append(it).append('\n') }
        result.warnings.distinct().take(MAX_ATTEMPT_WARNINGS).forEach { warning ->
            append("  warning=").append(warning.name.lowercase()).append('\n')
        }
    }

    private data class HandoffKey(
        val sessionId: String,
        val itemIds: List<String>,
    )

    private companion object {
        const val MAX_ATTEMPT_WARNINGS = 8
    }
}
