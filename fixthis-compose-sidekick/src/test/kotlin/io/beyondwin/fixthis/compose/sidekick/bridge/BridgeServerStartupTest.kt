package io.beyondwin.fixthis.compose.sidekick.bridge

import android.net.LocalServerSocket
import io.beyondwin.fixthis.compose.core.model.FixThisError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sun.misc.Unsafe
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

/**
 * Robolectric tests that exercise BridgeServer's retry loop via the injectable
 * `socketFactory` seam. We can't actually construct a real `LocalServerSocket`
 * under Robolectric (its native bind() fails), so [fakeServerSocket] uses
 * `Unsafe.allocateInstance` to materialise an empty subclass instance. The
 * BridgeServer never invokes `accept()` synchronously in `start()`; the
 * accept-loop coroutine launched on `scope` will silently bail on the first
 * call against the empty object, which is fine for these tests because we
 * stop() before tearing down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BridgeServerStartupTest {

    @Test
    fun startSucceedsOnFirstAttemptAndReportsBaseName() = runBlocking {
        val attempted = mutableListOf<String>()
        val baseName = "fixthis_test1"
        val server = newServer(baseName) { name ->
            attempted += name
            fakeServerSocket()
        }

        try {
            assertTrue("start() should succeed on the first attempt", server.start())
            assertEquals(listOf(baseName), attempted)
            assertEquals(baseName, server.resolvedSocketName())
        } finally {
            server.stop()
        }
    }

    @Test
    fun startRetriesWithSuffixFallbackWhenFirstNameIsBound() = runBlocking {
        val baseName = "fixthis_test2"
        val attempted = mutableListOf<String>()
        val server = newServer(baseName) { name ->
            attempted += name
            if (name == baseName) {
                throw IOException("simulated bind: $name already in use")
            }
            fakeServerSocket()
        }

        try {
            assertTrue("start() should succeed on the second attempt", server.start())
            assertEquals(listOf(baseName, "$baseName-1"), attempted)
            assertEquals("$baseName-1", server.resolvedSocketName())
        } finally {
            server.stop()
        }
    }

    @Test
    fun startReturnsFalseAfterThreeFailedAttempts() = runBlocking {
        val baseName = "fixthis_test3"
        val attempted = mutableListOf<String>()
        val server = newServer(baseName) { name ->
            attempted += name
            throw IOException("simulated bind failure on $name")
        }

        try {
            assertFalse(
                "start() should fail after exhausting all retry attempts",
                server.start(),
            )
            assertEquals(
                listOf(baseName, "$baseName-1", "$baseName-2"),
                attempted,
            )
            assertNull(server.resolvedSocketName())
        } finally {
            server.stop()
        }
    }

    @Test
    fun negotiatorProducesExpectedCandidates() {
        val base = "abstract-bridge"
        assertEquals(base, BridgeSocketNameNegotiator.nextCandidate(base, 0))
        assertEquals("$base-1", BridgeSocketNameNegotiator.nextCandidate(base, 1))
        assertEquals("$base-2", BridgeSocketNameNegotiator.nextCandidate(base, 2))
        assertEquals(3, BridgeSocketNameNegotiator.MaxAttempts)
    }

    @Test
    fun resolvedSocketNameSurfacesInStatus() = kotlinx.coroutines.runBlocking {
        val baseName = "fixthis_test5"
        val environment = StubEnvironment()
        val server = newServer(baseName, environment) { name ->
            if (name == baseName) throw IOException("simulated bind on $name")
            fakeServerSocket()
        }

        try {
            assertTrue(server.start())
            val response = server.handleRequestForTest(
                """{"id":"1","token":"token","method":"status"}""",
            )
            // The handshake (`status`) must surface the resolved socket name so
            // that the host can discover the negotiated fallback.
            assertTrue(
                "status response must include the resolved socket name: $response",
                response.contains(""""socketName": "$baseName-1""""),
            )
        } finally {
            server.stop()
        }
    }

    private fun newServer(
        baseSocketName: String,
        environment: BridgeEnvironment = StubEnvironment(),
        factory: (String) -> LocalServerSocket,
    ): BridgeServer = BridgeServer(
        session = SidekickSession(
            packageName = "io.beyondwin.fixthis.sample",
            socketName = baseSocketName,
            socketAddress = "localabstract:$baseSocketName",
            token = "token",
            sidekickVersion = "0.1.0-test",
            bridgeProtocolVersion = BridgeProtocol.VERSION,
            createdAtEpochMillis = 1L,
            processStartEpochMillis = 1L,
        ),
        environment = environment,
        socketFactory = factory,
    )

    private fun fakeServerSocket(): LocalServerSocket = unsafe.allocateInstance(LocalServerSocket::class.java) as LocalServerSocket

    private class StubEnvironment(
        private val cacheDirectory: File =
            createTempDirectory(prefix = "fixthis-startup").toFile().also { it.deleteOnExit() },
    ) : BridgeEnvironment {
        override suspend fun status(): BridgeStatus = BridgeStatus(
            activity = null,
            rootsCount = 0,
            sidekickVersion = "0.1.0-test",
            bridgeProtocolVersion = BridgeProtocol.VERSION,
            sourceIndexAvailable = false,
        )

        override suspend fun inspectCurrentScreen(): BridgeScreenInspection = BridgeScreenInspection(
            errors = listOf(FixThisError("NO_ACTIVITY", "stub")),
        )

        override suspend fun captureScreenSnapshot(): BridgeScreenSnapshot = BridgeScreenSnapshot(inspection = BridgeScreenInspection())

        override suspend fun readSourceIndex(): BridgeSourceIndexResult = BridgeSourceIndexResult(sourceIndexAvailable = false)

        override suspend fun getLastScreenSnapshot(): BridgeScreenSnapshot? = null

        override suspend fun performNavigation(request: BridgeNavigationRequest): BridgeNavigationResult = BridgeNavigationResult(performed = false, action = request.action)

        override fun screenshotCacheDirectory(): File = cacheDirectory
    }

    private companion object {
        val unsafe: Unsafe = Unsafe::class.java
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null) as Unsafe
    }
}
