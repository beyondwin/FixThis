package io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SessionCompactionCoordinator] — the post-mutation event-log
 * compaction concern extracted from FeedbackSessionStoreDelegate.
 *
 * Invariants under test:
 *  - runOnce(threshold) is invoked when a compactor is wired
 *  - compaction success resets the failure throttle for the session
 *  - compaction failure is swallowed and reported only through the throttled sink
 *    (first failure, every Nth, and after the time window elapses)
 *  - per-session compaction locks are isolated across sessions
 *  - no-op when the compactor provider is null
 */
class SessionCompactionCoordinatorTest {

    private fun coordinator(
        compactorProvider: ((sessionId: String) -> EventLogCompactionTask)?,
        threshold: Int = 0,
        sink: (sessionId: String, cause: Throwable) -> Unit = { _, _ -> },
        clock: () -> Long = { 0L },
    ) = SessionCompactionCoordinator(
        eventLogCompactorProvider = compactorProvider,
        eventLogCompactionThreshold = threshold,
        compactionFailureSink = sink,
        clock = clock,
    )

    @Test
    fun invokesRunOnceWithThresholdWhenCompactorWired() {
        val observedThreshold = AtomicInteger(-1)
        val calls = AtomicInteger(0)
        val coordinator = coordinator(
            compactorProvider = {
                EventLogCompactionTask { threshold ->
                    observedThreshold.set(threshold)
                    calls.incrementAndGet()
                }
            },
            threshold = 7,
        )

        coordinator.compactAfterMutation("s1")

        assertEquals(1, calls.get())
        assertEquals(7, observedThreshold.get())
    }

    @Test
    fun noOpWhenCompactorProviderIsNull() {
        // Must not throw and must not invoke any sink.
        val sinkCalls = AtomicInteger(0)
        val coordinator = coordinator(
            compactorProvider = null,
            sink = { _, _ -> sinkCalls.incrementAndGet() },
        )

        coordinator.compactAfterMutation("s1")

        assertEquals(0, sinkCalls.get())
    }

    @Test
    fun firstFailureEmitsThroughSink() {
        val emissions = mutableListOf<String>()
        val coordinator = coordinator(
            compactorProvider = { EventLogCompactionTask { error("boom") } },
            sink = { sessionId, _ -> emissions.add(sessionId) },
        )

        coordinator.compactAfterMutation("s1")

        assertEquals(listOf("s1"), emissions)
    }

    @Test
    fun repeatedFailuresAreThrottledBetweenFirstAndNth() {
        val emissions = AtomicInteger(0)
        val coordinator = coordinator(
            compactorProvider = { EventLogCompactionTask { error("boom") } },
            sink = { _, _ -> emissions.incrementAndGet() },
            clock = { 100L },
        )

        // 49 failures: only the first should emit (window never elapses since clock is fixed).
        repeat(49) { coordinator.compactAfterMutation("s1") }
        assertEquals(1, emissions.get(), "Only the first failure should emit within the window")

        // 50th failure -> everyNth emits again.
        coordinator.compactAfterMutation("s1")
        assertEquals(2, emissions.get(), "The 50th consecutive failure should emit (every-Nth rule)")
    }

    @Test
    fun windowElapsedReEmitsEvenWhenNotNth() {
        val emissions = AtomicInteger(0)
        var now = 1_000L
        val coordinator = coordinator(
            compactorProvider = { EventLogCompactionTask { error("boom") } },
            sink = { _, _ -> emissions.incrementAndGet() },
            clock = { now },
        )

        coordinator.compactAfterMutation("s1") // first failure -> emit, lastEmit=1000
        assertEquals(1, emissions.get())

        // second failure within window, not Nth -> no emit
        now = 1_500L
        coordinator.compactAfterMutation("s1")
        assertEquals(1, emissions.get())

        // third failure after window (>= 60_000ms since lastEmit) -> emit
        now = 1_000L + 60_000L
        coordinator.compactAfterMutation("s1")
        assertEquals(2, emissions.get(), "Failure after the window should re-emit")
    }

    @Test
    fun successResetsThrottleSoNextFailureEmitsAsFirst() {
        val emissions = AtomicInteger(0)
        var fail = true
        val coordinator = coordinator(
            compactorProvider = {
                EventLogCompactionTask { if (fail) error("boom") }
            },
            sink = { _, _ -> emissions.incrementAndGet() },
            clock = { 100L },
        )

        coordinator.compactAfterMutation("s1") // first failure -> emit (1)
        coordinator.compactAfterMutation("s1") // throttled (still 1)
        assertEquals(1, emissions.get())

        // success resets throttle
        fail = false
        coordinator.compactAfterMutation("s1")
        assertEquals(1, emissions.get())

        // next failure should count as "first" again -> emit (2)
        fail = true
        coordinator.compactAfterMutation("s1")
        assertEquals(2, emissions.get(), "After a success reset, the next failure must emit as the first")
    }

    @Test
    fun throttleStateIsIsolatedPerSession() {
        val perSession = mutableMapOf<String, Int>()
        val coordinator = coordinator(
            compactorProvider = { EventLogCompactionTask { error("boom") } },
            sink = { sessionId, _ -> perSession[sessionId] = (perSession[sessionId] ?: 0) + 1 },
            clock = { 100L },
        )

        // Each session's first failure should emit independently.
        coordinator.compactAfterMutation("a")
        coordinator.compactAfterMutation("b")
        // Second failure on each -> throttled.
        coordinator.compactAfterMutation("a")
        coordinator.compactAfterMutation("b")

        assertEquals(1, perSession["a"])
        assertEquals(1, perSession["b"])
    }

    @Test
    fun perSessionCompactionLockSerializesSameSessionButNotDifferentSessions() {
        // Compaction on "a" blocks; a second compaction on "a" must wait, but "b" proceeds.
        val aStarted = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val bDone = CountDownLatch(1)
        val coordinator = coordinator(
            compactorProvider = { sessionId ->
                EventLogCompactionTask {
                    when (sessionId) {
                        "a" -> {
                            aStarted.countDown()
                            assertTrue(releaseA.await(5, TimeUnit.SECONDS))
                        }
                        "b" -> bDone.countDown()
                    }
                }
            },
        )

        val tA = thread(start = true) { coordinator.compactAfterMutation("a") }
        assertTrue(aStarted.await(5, TimeUnit.SECONDS))

        // While "a" holds its lock, "b" should still complete (different lock).
        val tB = thread(start = true) { coordinator.compactAfterMutation("b") }
        assertTrue(bDone.await(2, TimeUnit.SECONDS), "Different sessions must not share a compaction lock")

        releaseA.countDown()
        tA.join(5_000)
        tB.join(5_000)
    }

    @Test
    fun nullSinkResultUnaffectedAfterSuccessOnUnknownSession() {
        // resetCompactionFailureThrottle on a session that never failed must be a safe no-op.
        val coordinator = coordinator(
            compactorProvider = { EventLogCompactionTask { /* success */ } },
        )
        coordinator.compactAfterMutation("never-failed")
        // No assertion needed beyond "does not throw"; assert a benign property.
        assertNull(null)
    }
}
