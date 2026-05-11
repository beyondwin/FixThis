package io.beyondwin.fixthis.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InFlightRegistryTest {
    @Test
    fun fanOutThenCancelHalfAndDrainLeavesNoJobsActive() = runBlocking {
        val registry = InFlightRegistry()
        val gate = CompletableDeferred<Unit>()
        val total = 32
        val jobs = mutableListOf<Pair<String, Job>>()

        coroutineScope {
            repeat(total) { index ->
                val key = "req-$index"
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    // Suspend until released or cancelled.
                    gate.await()
                }
                registry.register(key, InFlightRequest("tools/call", job))
                jobs += key to job
            }

            assertEquals(total, registry.size())

            // Cancel every other request via remove + cancelAndJoin
            // (mirrors what notifications/cancelled would do).
            val cancelKeys = jobs.filterIndexed { i, _ -> i % 2 == 0 }.map { it.first }
            for (key in cancelKeys) {
                val removed = registry.remove(key)
                assertNotNull(removed, "expected request $key to be present")
                removed.job.cancelAndJoin()
            }

            assertEquals(total - cancelKeys.size, registry.size())
            // Cancelled jobs must already be done.
            for (key in cancelKeys) {
                val job = jobs.first { it.first == key }.second
                assertTrue(job.isCancelled, "expected $key to be cancelled")
                assertTrue(job.isCompleted, "expected $key to be completed")
            }
            // remove() on an already-removed key must return null.
            for (key in cancelKeys) {
                assertNull(registry.remove(key))
            }

            // Drain the remainder (mirrors cancelInFlightRequests).
            val drained = registry.consumeAll()
            assertEquals(total - cancelKeys.size, drained.size)
            assertEquals(0, registry.size())
            drained.forEach { it.job.cancelAndJoin() }

            // After draining, every job must be terminal.
            for ((_, job) in jobs) {
                assertTrue(
                    job.isCompleted || job.isCancelled,
                    "expected every job to be completed or cancelled after drain",
                )
                assertTrue(!job.isActive, "expected no active jobs after drain")
            }
        }
    }

    @Test
    fun consumeAllIsAtomicReadAndClear() = runBlocking {
        val registry = InFlightRegistry()
        val keys = listOf("a", "b", "c")
        val jobs = keys.map { key ->
            val job = launch(start = CoroutineStart.LAZY) { /* never started */ }
            registry.register(key, InFlightRequest("tools/call", job))
            job
        }

        val drained = registry.consumeAll()

        assertEquals(keys.size, drained.size)
        assertEquals(0, registry.size())
        // Subsequent consumeAll returns empty.
        assertTrue(registry.consumeAll().isEmpty())
        // Cleanup lazy jobs so coroutineScope completes.
        jobs.forEach { it.cancel() }
    }

    @Test
    fun removeReturnsNullForUnknownKey() = runBlocking {
        val registry = InFlightRegistry()
        assertNull(registry.remove("missing"))
    }
}
