package io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog

private const val COMPACTION_FAILURE_EMIT_EVERY = 50
private const val COMPACTION_FAILURE_EMIT_WINDOW_MILLIS = 60_000L

private fun logCompactionFailure(sessionId: String, cause: Throwable) {
    System.err.println(
        "WARN: event-log compaction failed for session $sessionId: " +
            (cause.message ?: cause::class.java.simpleName),
    )
}

private class CompactionFailureThrottleState(
    var consecutiveFailures: Int = 0,
    var lastEmitAtEpochMillis: Long = Long.MIN_VALUE,
)

/**
 * Owns the post-mutation event-log compaction concern extracted from
 * FeedbackSessionStoreDelegate: the per-session compaction lock, the
 * compaction-failure throttle, and the throttled failure sink.
 *
 * [compactAfterMutation] is invoked by the delegate at the TAIL of its mutation
 * wrappers — OUTSIDE the delegate's main lock — so a slow compaction never blocks
 * concurrent reads. Per-session compaction is serialized via [compactionLock]; the
 * throttle state map and the locks map are guarded by this coordinator's own
 * internal [lock] (NOT the delegate's lock).
 *
 * Compaction is a best-effort background optimization. A failure leaves the valid,
 * uncompacted event log intact and is retried on the next mutation, so it is NEVER
 * surfaced as a skipped/corrupt session signal — it is reported only through the
 * throttled WARN sink.
 */
internal class SessionCompactionCoordinator(
    private val eventLogCompactorProvider: ((sessionId: String) -> EventLogCompactionTask)?,
    private val eventLogCompactionThreshold: Int,
    compactionFailureSink: ((sessionId: String, cause: Throwable) -> Unit)? = null,
    private val clock: () -> Long,
) {
    private val compactionFailureSink = compactionFailureSink ?: ::logCompactionFailure
    private val lock = Any()
    private val compactionLocks = mutableMapOf<String, Any>()
    private val compactionFailureThrottle = mutableMapOf<String, CompactionFailureThrottleState>()

    @Suppress("TooGenericExceptionCaught")
    fun compactAfterMutation(sessionId: String) {
        val compactor = eventLogCompactorProvider?.invoke(sessionId) ?: return
        try {
            synchronized(compactionLock(sessionId)) {
                compactor.runOnce(eventLogCompactionThreshold)
            }
            resetCompactionFailureThrottle(sessionId)
        } catch (error: Exception) {
            // Compaction is a best-effort background optimization. A failure leaves the
            // valid, uncompacted event log intact and is retried on the next mutation, so it
            // must NOT be surfaced as a skipped/corrupt session (that signal means the data
            // could not be loaded). The mutation has already committed successfully. The
            // failure is reported through a throttled WARN sink so a hot mutation loop on a
            // persistently failing compactor cannot spam the log.
            if (shouldEmitCompactionFailure(sessionId)) {
                compactionFailureSink(sessionId, error)
            }
        }
    }

    private fun shouldEmitCompactionFailure(sessionId: String): Boolean = synchronized(lock) {
        val state = compactionFailureThrottle.getOrPut(sessionId) { CompactionFailureThrottleState() }
        state.consecutiveFailures += 1
        val now = clock()
        val firstFailure = state.consecutiveFailures == 1
        val everyNth = state.consecutiveFailures % COMPACTION_FAILURE_EMIT_EVERY == 0
        val windowElapsed = state.lastEmitAtEpochMillis != Long.MIN_VALUE &&
            now - state.lastEmitAtEpochMillis >= COMPACTION_FAILURE_EMIT_WINDOW_MILLIS
        val emit = firstFailure || everyNth || windowElapsed
        if (emit) state.lastEmitAtEpochMillis = now
        emit
    }

    private fun resetCompactionFailureThrottle(sessionId: String) = synchronized(lock) {
        compactionFailureThrottle.remove(sessionId)
    }

    private fun compactionLock(sessionId: String): Any = synchronized(lock) {
        compactionLocks.getOrPut(sessionId) { Any() }
    }
}
