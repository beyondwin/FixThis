@file:Suppress("MaxLineLength")

package io.github.beyondwin.fixthis.compose.sidekick.bridge

import android.net.LocalServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import sun.misc.Unsafe
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BridgeServerConcurrencyTest {

    @Test
    fun concurrentStartReturnsTrueOnceAndBindsOnlyOnce(): Unit = runBlocking {
        val bindCount = AtomicInteger(0)
        val server = newServerWithCountingSocketFactory(bindCount)

        val results = withTimeout(5.seconds) {
            (0 until 8).map { async { server.start() } }.awaitAll()
        }
        try {
            assertEquals("exactly one start should win", 1, results.count { it })
            assertEquals("the rest must return false", 7, results.count { !it })
            assertEquals("socketFactory must bind exactly once", 1, bindCount.get())
        } finally {
            server.stop()
        }
    }

    @Test(timeout = 15_000)
    @Config(shadows = [JvmLoopbackServerSocketShadow::class, JvmLoopbackClientSocketShadow::class])
    fun stopWaitsForAcceptedHandlerCancellationCleanup(): Unit = runBlocking {
        // Substitute only the native socket boundary. The real accept loop,
        // framing, authentication, router and handler coroutine still run.
        val environment = BlockingBridgeEnvironment()
        lateinit var listeningSocket: JvmLoopbackServerSocketShadow
        val server = BridgeServer(
            session = fixedSession(),
            environment = environment,
            socketFactory = { name ->
                LocalServerSocket(name).also { listeningSocket = Shadow.extract(it) }
            },
        )

        try {
            assertTrue("the test listener must start", server.start())
            Socket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", listeningSocket.port), 2_000)
                client.soTimeout = 10_000
                val response = async(Dispatchers.IO) {
                    BridgeProtocol.writeFrame(client.getOutputStream(), statusRequestPayload(server.session.token))
                    BridgeProtocol.readFrame(client.getInputStream())
                }

                withTimeout(5.seconds) { environment.entered.await() }
                val stopping = async { server.stop() }
                withTimeout(5.seconds) { environment.cleanupEntered.await() }
                assertFalse("pending work must have been cancelled", environment.releaseWork.isCompleted)
                assertEquals(BridgeServerState.Stopping, server.state.value)
                assertFalse("stop must join handler cleanup", stopping.isCompleted)
                assertFalse(environment.finished.isCompleted)

                environment.releaseCleanup.complete(Unit)
                withTimeout(5.seconds) { stopping.await() }
                assertTrue("handler cleanup must finish before stop returns", environment.finished.isCompleted)
                assertEquals(BridgeServerState.Idle, server.state.value)
                assertNull("cancelled handler must close its accepted client", withTimeout(5.seconds) { response.await() })
            }
        } finally {
            environment.releaseWork.complete(Unit)
            environment.releaseCleanup.complete(Unit)
            withContext(NonCancellable) { server.stop() }
        }
    }

    @Test
    fun observedStateIsConsistentAcrossSingleLifecycle(): Unit = runBlocking {
        // T3: BridgeServer is single-use (spec G2a). One server, one lifecycle.
        // We assert that observed emissions form a legal subsequence — StateFlow
        // conflation may collapse Idle→Starting→Running into Idle→Running, which
        // is acceptable. Illegal pairs (Running→Starting, Running→Running with
        // blank name, etc.) are not.
        val server = newServerWithCountingSocketFactory(AtomicInteger(0))
        val seen = mutableListOf<BridgeServerState>()
        val collector = launch { server.state.collect { seen += it } }
        yield() // let collector observe initial Idle before start() overwrites it

        server.start()
        yield() // let collector observe Running before stop() overwrites it
        server.stop()
        delay(50) // allow trailing emission to land
        collector.cancel()

        assertEquals("must start in Idle", BridgeServerState.Idle, seen.first())
        assertEquals("must end in Idle", BridgeServerState.Idle, seen.last())
        assertTrue(
            "must observe at least one Running with non-blank socket name",
            seen.any { it is BridgeServerState.Running && it.socketName.isNotBlank() },
        )
        // No two adjacent emissions are illegal.
        seen.zipWithNext().forEach { (a, b) ->
            require(a != b) { "duplicate adjacent emission: $a" }
            require(!(a is BridgeServerState.Running && b is BridgeServerState.Starting)) {
                "illegal Running→Starting transition"
            }
        }
        // resolvedSocketName agrees with the latest Running observation.
        val lastRunning = seen.filterIsInstance<BridgeServerState.Running>().lastOrNull()
        if (lastRunning != null) {
            // After stop(), resolvedSocketName must be null.
            assertEquals(null, server.resolvedSocketName())
        }
    }

    // === fixtures ===

    private fun newServerWithCountingSocketFactory(counter: AtomicInteger): BridgeServer {
        val session = fixedSession()
        val env = StubBridgeEnvironment()
        return BridgeServer(
            session = session,
            environment = env,
            socketFactory = { _ ->
                counter.incrementAndGet()
                fakeServerSocket()
            },
        )
    }

    private fun fakeServerSocket(): LocalServerSocket = unsafe.allocateInstance(LocalServerSocket::class.java) as LocalServerSocket

    private class BlockingBridgeEnvironment : BridgeEnvironment by StubBridgeEnvironment() {
        val entered = CompletableDeferred<Unit>()
        val releaseWork = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()

        override suspend fun status(): BridgeStatus {
            entered.complete(Unit)
            try {
                releaseWork.await()
                return BridgeStatus(
                    activity = null,
                    rootsCount = 0,
                    sidekickVersion = "test",
                    bridgeProtocolVersion = BridgeProtocol.VERSION,
                    sourceIndexAvailable = false,
                )
            } finally {
                cleanupEntered.complete(Unit)
                withContext(NonCancellable) {
                    releaseCleanup.await()
                    finished.complete(Unit)
                }
            }
        }
    }

    private fun statusRequestPayload(token: String): String = """{"id":"1","method":"status","params":{},"token":"$token"}"""

    private fun fixedSession(): SidekickSession = SidekickSession(
        packageName = "io.github.beyondwin.fixthis.sample",
        socketName = "fixthis_io.github.beyondwin.fixthis.sample",
        socketAddress = "localabstract:fixthis_io.github.beyondwin.fixthis.sample",
        token = "token",
        sidekickVersion = "0.1.0-test",
        bridgeProtocolVersion = BridgeProtocol.VERSION,
        createdAtEpochMillis = 1234L,
        processStartEpochMillis = 1234L,
    )

    private class StubBridgeEnvironment(
        private val screenshotCacheDir: File =
            Files.createTempDirectory("fixthis-cache").toFile().also { it.deleteOnExit() },
    ) : BridgeEnvironment {
        override suspend fun status(): BridgeStatus = BridgeStatus(
            activity = null,
            rootsCount = 0,
            sidekickVersion = "0.1.0-test",
            bridgeProtocolVersion = BridgeProtocol.VERSION,
            sourceIndexAvailable = false,
        )

        override suspend fun inspectCurrentScreen(): BridgeScreenInspection = BridgeScreenInspection(activity = null)

        override suspend fun captureScreenSnapshot(currentFocusOutput: String?): BridgeScreenSnapshot = BridgeScreenSnapshot(inspection = BridgeScreenInspection(activity = null))

        override suspend fun readSourceIndex(): BridgeSourceIndexResult = BridgeSourceIndexResult(sourceIndexAvailable = false)

        override suspend fun getLastScreenSnapshot(): BridgeScreenSnapshot? = null

        override suspend fun performNavigation(request: BridgeNavigationRequest): BridgeNavigationResult = BridgeNavigationResult(performed = false, action = request.action)

        override fun screenshotCacheDirectory(): File = screenshotCacheDir
    }

    private companion object {
        val unsafe: Unsafe = Unsafe::class.java
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null) as Unsafe
    }
}
