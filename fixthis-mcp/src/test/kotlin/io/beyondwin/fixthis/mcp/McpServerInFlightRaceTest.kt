package io.beyondwin.fixthis.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertNull

class McpServerInFlightRaceTest {
    private fun request(idKey: String, method: String = "tools/call"): McpRequest = McpRequest(
        id = JsonPrimitive(idKey),
        idKey = idKey,
        method = method,
        params = JsonObject(emptyMap()),
    )

    @Test
    fun trackedRequestRegistersEvenWhenJobCompletesImmediately() = runBlocking {
        val registry = InFlightRegistry()
        val server = McpServer()
        val message = request("id-1")

        server.trackRequestForTest(
            message = message,
            registry = registry,
            writeResponse = { /* noop */ },
            handleRequest = { _ -> null /* completes immediately, no response written */ },
        )

        // After immediate completion, registry must not retain the entry.
        assertNull(registry.snapshot()[message.idKey])
    }

    @Test
    fun cancellingTrackedRequestRemovesFromRegistry() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val registry = InFlightRegistry()
        val server = McpServer()
        val message = request("id-2")

        val job = launch {
            server.trackRequestForTest(
                message = message,
                registry = registry,
                writeResponse = { /* noop */ },
                handleRequest = { _ ->
                    gate.await()
                    null
                },
            )
        }

        while (registry.snapshot()[message.idKey] == null) {
            yield()
        }

        registry.remove(message.idKey)?.job?.cancel()
        gate.complete(Unit)
        job.join()

        assertNull(registry.snapshot()[message.idKey])
    }
}
