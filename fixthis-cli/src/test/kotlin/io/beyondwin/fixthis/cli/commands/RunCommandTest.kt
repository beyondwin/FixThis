package io.beyondwin.fixthis.cli.commands

import java.io.IOException
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RunCommandTest {
    @Test
    fun waitForStatusCancellationReturnsWithin50ms() = runBlocking {
        val job = launch {
            waitForStatus(timeoutMillis = 10_000L) {
                throw IOException("sidekick not ready")
            }
        }
        // Let it enter at least one backoff sleep.
        delay(50)
        val elapsed = measureTimeMillis { job.cancelAndJoin() }
        assertTrue("cancellation took ${elapsed}ms, expected < 50ms", elapsed < 50)
    }

    @Test
    fun waitForStatusReturnsOnFirstSuccess() = runBlocking {
        var calls = 0
        waitForStatus(timeoutMillis = 1_000L) {
            calls++
        }
        assertTrue("probe should be invoked at least once, was $calls", calls >= 1)
    }

    @Test
    fun waitForStatusThrowsOnTimeoutWithLastErrorAsCause() = runBlocking {
        val expected = IOException("sidekick offline")
        try {
            waitForStatus(timeoutMillis = 50L) {
                throw expected
            }
            fail("expected IllegalStateException")
        } catch (error: IllegalStateException) {
            assertTrue(
                "expected last error to be wrapped as cause but was ${error.cause}",
                error.cause === expected,
            )
        }
    }
}
