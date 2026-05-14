package io.beyondwin.fixthis.compose.sidekick.bridge

import android.net.LocalServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sun.misc.Unsafe
import java.io.File
import java.nio.file.Files

/**
 * Spec §5.3 stress harness: covers the §1.2 zombie-handler invariant in lieu of
 * the (deferred) real-socket T2 in [BridgeServerConcurrencyTest]. For two
 * seconds, one coroutine repeatedly constructs/starts/stops fresh BridgeServer
 * instances (per spec G2a single-use contract) while a second coroutine issues
 * a storm of in-process requests through [BridgeServer.handleRequestForTest].
 *
 * Assertions: no IllegalStateException, NullPointerException, or other
 * unexpected throwable bubbles out of start()/stop()/handleRequestForTest().
 * Any caught error fails the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BridgeServerConcurrencyStressTest {

    @Test
    fun twoSecondStartStopAndRequestStormHasNoCrash() = runBlocking {
        val deadline = System.currentTimeMillis() + 2_000
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        val cycleJob = launch(Dispatchers.Default) {
            while (System.currentTimeMillis() < deadline) {
                val server = newStressServer()
                runCatching { server.start() }.onFailure { errors += it }
                runCatching { server.stop() }.onFailure { errors += it }
            }
        }
        val requestJob = launch(Dispatchers.Default) {
            val server = newStressServer()
            server.start()
            try {
                repeat(200) {
                    runCatching {
                        server.handleRequestForTest(statusRequestPayload(server.session.token))
                    }.onFailure { errors += it }
                }
            } finally {
                runCatching { server.stop() }.onFailure { errors += it }
            }
        }
        cycleJob.join()
        requestJob.join()
        assertEquals(emptyList<Throwable>(), errors.toList())
    }

    // === fixtures (mirror BridgeServerConcurrencyTest) ===

    private fun newStressServer(): BridgeServer = BridgeServer(
        session = fixedSession(),
        environment = StubBridgeEnvironment(),
        socketFactory = { _ -> fakeServerSocket() },
    )

    private fun fakeServerSocket(): LocalServerSocket =
        unsafe.allocateInstance(LocalServerSocket::class.java) as LocalServerSocket

    private fun statusRequestPayload(token: String): String =
        """{"id":"1","method":"status","params":{},"token":"$token"}"""

    private fun fixedSession(): SidekickSession = SidekickSession(
        packageName = "io.beyondwin.fixthis.sample",
        socketName = "fixthis_io.beyondwin.fixthis.sample",
        socketAddress = "localabstract:fixthis_io.beyondwin.fixthis.sample",
        token = "token",
        sidekickVersion = "0.1.0-test",
        bridgeProtocolVersion = BridgeProtocol.VERSION,
        createdAtEpochMillis = 1234L,
        processStartEpochMillis = 1234L,
    )

    private class StubBridgeEnvironment(
        private val screenshotCacheDir: File =
            Files.createTempDirectory("fixthis-stress-cache").toFile().also { it.deleteOnExit() },
    ) : BridgeEnvironment {
        override suspend fun status(): BridgeStatus = BridgeStatus(
            activity = null,
            rootsCount = 0,
            sidekickVersion = "0.1.0-test",
            bridgeProtocolVersion = BridgeProtocol.VERSION,
            sourceIndexAvailable = false,
        )

        override suspend fun inspectCurrentScreen(): BridgeScreenInspection =
            BridgeScreenInspection(activity = null)

        override suspend fun captureScreenSnapshot(): BridgeScreenSnapshot =
            BridgeScreenSnapshot(inspection = BridgeScreenInspection(activity = null))

        override suspend fun readSourceIndex(): BridgeSourceIndexResult =
            BridgeSourceIndexResult(sourceIndexAvailable = false)

        override suspend fun getLastScreenSnapshot(): BridgeScreenSnapshot? = null

        override suspend fun performNavigation(request: BridgeNavigationRequest): BridgeNavigationResult =
            BridgeNavigationResult(performed = false, action = request.action)

        override fun screenshotCacheDirectory(): File = screenshotCacheDir
    }

    private companion object {
        val unsafe: Unsafe = Unsafe::class.java
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null) as Unsafe
    }
}
