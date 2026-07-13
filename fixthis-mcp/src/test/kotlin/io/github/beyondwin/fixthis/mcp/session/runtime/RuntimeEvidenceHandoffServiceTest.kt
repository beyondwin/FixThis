package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackDelivery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeEvidenceHandoffServiceTest {
    @Test
    fun autoCollectionFinishesBeforeRereadRenderAndMarkSent() = runBlocking {
        val calls = mutableListOf<String>()
        var reads = 0
        var capturedRequest: RuntimeEvidenceCaptureRequest? = null
        val service = RuntimeEvidenceHandoffService(
            readSession = {
                calls += if (reads++ == 0) "read-before" else "read-after"
                draftSession(RuntimeEvidencePolicy.AUTO_ON_HANDOFF)
            },
            collect = { request ->
                calls += "collect"
                capturedRequest = request
                completeCapture()
            },
            render = { _, _ ->
                calls += "render"
                "final prompt"
            },
            markSent = { _, prompt, _ ->
                assertTrue(prompt.startsWith("final prompt\n\nruntimeEvidenceAttempt:"))
                calls += "mark-sent"
                sentSession(RuntimeEvidencePolicy.AUTO_ON_HANDOFF)
            },
        )

        val result = service.sendDraftToAgentWithRuntimeEvidence("s1", listOf("i1"))

        assertEquals(listOf("read-before", "collect", "read-after", "render", "mark-sent"), calls)
        assertEquals(RuntimeEvidencePreset.BASELINE, capturedRequest?.preset)
        assertEquals(RuntimeEvidenceTrigger.HANDOFF_AUTO, capturedRequest?.trigger)
        assertEquals("screen1", capturedRequest?.screenId)
        assertEquals(listOf("i1"), capturedRequest?.itemIds)
        assertTrue(result.prompt.contains("runtimeEvidenceAttempt:"))
        assertTrue(result.prompt.contains("status=complete"))
        assertEquals(RuntimeEvidenceStatus.COMPLETE, result.runtimeEvidence.status)
        assertTrue(result.session.items.all { it.delivery == FeedbackDelivery.SENT })
    }

    @Test
    fun manualAndOffSkipCollectionButStillSend() = runBlocking {
        for ((policy, reason) in listOf(RuntimeEvidencePolicy.MANUAL to "manual", RuntimeEvidencePolicy.OFF to "off")) {
            val collectCalls = AtomicInteger()
            val service = service(
                policy = policy,
                collect = {
                    collectCalls.incrementAndGet()
                    completeCapture()
                },
            )

            val result = service.sendDraftToAgentWithRuntimeEvidence("s1", listOf("i1"))

            assertEquals(0, collectCalls.get())
            assertFalse(result.runtimeEvidence.attempted)
            assertEquals(reason, result.runtimeEvidence.skippedReason)
            assertTrue(result.prompt.contains("status=skipped"))
            assertTrue(result.prompt.contains("reason=$reason"))
            assertTrue(result.session.items.all { it.delivery == FeedbackDelivery.SENT })
        }
    }

    @Test
    fun typedCaptureFailureStillSendsButMarkFailurePropagates() = runBlocking {
        val failedCapture = RuntimeEvidenceCaptureResult(
            attempted = true,
            status = RuntimeEvidenceStatus.FAILED,
            failureReason = RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT,
        )
        val sent = service(policy = RuntimeEvidencePolicy.AUTO_ON_HANDOFF, collect = { failedCapture })
            .sendDraftToAgentWithRuntimeEvidence("s1", listOf("i1"))
        assertEquals(RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT, sent.runtimeEvidence.failureReason)
        assertTrue(sent.prompt.contains("status=failed"))
        assertTrue(sent.prompt.contains("failure=capture_timeout"))
        assertFalse(sent.prompt.contains("artifactDirectory"))
        assertTrue(sent.session.items.all { it.delivery == FeedbackDelivery.SENT })

        val unsupportedCapture = RuntimeEvidenceCaptureResult(
            attempted = true,
            status = RuntimeEvidenceStatus.UNSUPPORTED,
            failureReason = RuntimeEvidenceFailureReason.COLLECTOR_UNSUPPORTED,
        )
        val unsupported = service(policy = RuntimeEvidencePolicy.AUTO_ON_HANDOFF, collect = { unsupportedCapture })
            .sendDraftToAgentWithRuntimeEvidence("s1", listOf("i1"))
        assertTrue(unsupported.prompt.contains("status=unsupported"))
        assertTrue(unsupported.prompt.contains("failure=collector_unsupported"))

        val failure = IllegalStateException("mark failed")
        val broken = service(
            policy = RuntimeEvidencePolicy.AUTO_ON_HANDOFF,
            collect = { failedCapture },
            markSent = { _, _, _ -> throw failure },
        )
        assertEquals(
            failure,
            assertFailsWith<IllegalStateException> {
                broken.sendDraftToAgentWithRuntimeEvidence("s1", listOf("i1"))
            },
        )
    }

    @Test
    fun draftItemsRemainOutsideTheDefaultAgentQueueUntilCollectionEnds() = runBlocking {
        var current = draftSession(RuntimeEvidencePolicy.AUTO_ON_HANDOFF)
        var collectionFinished = false
        val service = RuntimeEvidenceHandoffService(
            readSession = { current },
            collect = {
                assertTrue(current.items.none { it.delivery == FeedbackDelivery.SENT })
                assertTrue(current.handoffBatches.isEmpty())
                collectionFinished = true
                completeCapture()
            },
            render = { _, _ ->
                assertTrue(collectionFinished)
                "prompt"
            },
            markSent = { _, _, _ ->
                assertTrue(collectionFinished)
                sentSession(RuntimeEvidencePolicy.AUTO_ON_HANDOFF).also { current = it }
            },
        )

        val result = service.sendDraftToAgentWithRuntimeEvidence("s1", listOf("i1"))

        assertTrue(result.session.items.all { it.delivery == FeedbackDelivery.SENT })
    }

    @Test
    fun concurrentEquivalentSavesShareOneCompleteHandoffFlow() = runBlocking {
        val collectCalls = AtomicInteger()
        val renderCalls = AtomicInteger()
        val markCalls = AtomicInteger()
        val collectStarted = CompletableDeferred<Unit>()
        val releaseCollect = CompletableDeferred<Unit>()
        val service = service(
            policy = RuntimeEvidencePolicy.AUTO_ON_HANDOFF,
            collect = {
                collectCalls.incrementAndGet()
                collectStarted.complete(Unit)
                releaseCollect.await()
                completeCapture()
            },
            render = { _, _ ->
                renderCalls.incrementAndGet()
                "shared prompt"
            },
            markSent = { _, _, _ ->
                markCalls.incrementAndGet()
                sentSession(RuntimeEvidencePolicy.AUTO_ON_HANDOFF)
            },
        )

        val first = async { service.sendDraftToAgentWithRuntimeEvidence("s1", listOf("i1", "i1")) }
        collectStarted.await()
        val second = async { service.sendDraftToAgentWithRuntimeEvidence("s1", listOf("i1")) }
        yield()
        releaseCollect.complete(Unit)
        val results = awaitAll(first, second)

        assertEquals(1, collectCalls.get())
        assertEquals(1, renderCalls.get())
        assertEquals(1, markCalls.get())
        assertEquals(results[0], results[1])
    }

    @Test
    fun failedSharedHandoffPropagatesAndReleasesTheDedupeEntry() = runBlocking {
        val attempts = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(
            policy = RuntimeEvidencePolicy.AUTO_ON_HANDOFF,
            collect = {
                if (attempts.incrementAndGet() == 1) {
                    started.complete(Unit)
                    release.await()
                    error("capture infrastructure failed")
                }
                completeCapture()
            },
        )
        val first = async { runCatching { service.sendDraftToAgentWithRuntimeEvidence("s1", listOf("i1")) } }
        started.await()
        val second = async { runCatching { service.sendDraftToAgentWithRuntimeEvidence("s1", listOf("i1")) } }
        yield()
        release.complete(Unit)
        val failed = awaitAll(first, second)

        assertTrue(failed.all { it.exceptionOrNull()?.message == "capture infrastructure failed" })
        val retry = service.sendDraftToAgentWithRuntimeEvidence("s1", listOf("i1"))
        assertEquals(RuntimeEvidenceStatus.COMPLETE, retry.runtimeEvidence.status)
        assertEquals(2, attempts.get())
    }

    @Test
    fun ownerCancellationReleasesTheDedupeEntryForRetry() = runBlocking {
        val attempts = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val service = service(
            policy = RuntimeEvidencePolicy.AUTO_ON_HANDOFF,
            collect = {
                if (attempts.incrementAndGet() == 1) {
                    started.complete(Unit)
                    awaitCancellation()
                }
                completeCapture()
            },
        )
        val cancelled = launch {
            service.sendDraftToAgentWithRuntimeEvidence("s1", listOf("i1"))
        }
        started.await()
        cancelled.cancelAndJoin()

        val retry = service.sendDraftToAgentWithRuntimeEvidence("s1", listOf("i1"))

        assertEquals(RuntimeEvidenceStatus.COMPLETE, retry.runtimeEvidence.status)
        assertEquals(2, attempts.get())
    }

    private fun service(
        policy: RuntimeEvidencePolicy,
        collect: suspend (RuntimeEvidenceCaptureRequest) -> RuntimeEvidenceCaptureResult,
        render: (SessionDto, List<String>) -> String = { _, _ -> "prompt" },
        markSent: (String, String, List<String>) -> SessionDto = { _, _, _ -> sentSession(policy) },
    ): RuntimeEvidenceHandoffService = RuntimeEvidenceHandoffService(
        readSession = { draftSession(policy) },
        collect = collect,
        render = render,
        markSent = markSent,
    )
}

private fun completeCapture() = RuntimeEvidenceCaptureResult(
    attempted = true,
    captureId = "capture-1",
    status = RuntimeEvidenceStatus.COMPLETE,
    linkedItemIds = listOf("i1"),
)

private fun draftSession(policy: RuntimeEvidencePolicy) = handoffSession(policy, FeedbackDelivery.DRAFT)

private fun sentSession(policy: RuntimeEvidencePolicy) = handoffSession(policy, FeedbackDelivery.SENT)

private fun handoffSession(policy: RuntimeEvidencePolicy, delivery: FeedbackDelivery) = SessionDto(
    sessionId = "s1",
    packageName = "io.github.beyondwin.fixthis.sample",
    projectRoot = "/tmp/s1",
    createdAtEpochMillis = 1,
    updatedAtEpochMillis = 1,
    runtimeEvidencePolicy = policy,
    screens = listOf(SnapshotDto("screen1", 1_000, displayName = "Screen")),
    items = listOf(
        AnnotationDto(
            itemId = "i1",
            screenId = "screen1",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
            comment = "comment",
            delivery = delivery,
        ),
    ),
)
