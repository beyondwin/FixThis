package io.beyondwin.fixthis.mcp

import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class InFlightRequest(val method: String, val job: Job)

/**
 * Thread-safe registry of in-flight MCP requests keyed by request id.
 *
 * A single [Mutex] guards the underlying map. Critical sections only manipulate
 * the map; callers are expected to perform [Job.cancelAndJoin] (or equivalent)
 * outside the lock with the returned [InFlightRequest] values.
 */
internal class InFlightRegistry {
    private val mutex = Mutex()
    private val requests = mutableMapOf<String, InFlightRequest>()

    /**
     * Register [request] under [key]. If a request is already registered under
     * the same key, it is replaced (matching the prior `inFlight[key] = …`
     * behaviour in `McpServer`). The replaced request is returned so callers
     * may decide how to terminate it; the previous code silently dropped it.
     */
    suspend fun register(key: String, request: InFlightRequest): InFlightRequest? = mutex.withLock { requests.put(key, request) }

    /** Atomically remove and return the request associated with [key], if any. */
    suspend fun remove(key: String): InFlightRequest? = mutex.withLock { requests.remove(key) }

    /**
     * Atomically snapshot every registered request and clear the registry in
     * a single critical section. The caller cancels/joins the returned jobs
     * outside the lock.
     */
    suspend fun consumeAll(): List<InFlightRequest> = mutex.withLock {
        val snapshot = requests.values.toList()
        requests.clear()
        snapshot
    }

    /** Current number of registered requests. Exposed for tests. */
    suspend fun size(): Int = mutex.withLock { requests.size }

    /**
     * Read-only snapshot of currently registered requests. Exposed for tests
     * exercising race conditions around register/remove ordering.
     */
    suspend fun snapshot(): Map<String, InFlightRequest> = mutex.withLock { requests.toMap() }
}
