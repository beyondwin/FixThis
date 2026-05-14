package io.beyondwin.fixthis.compose.sidekick.bridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sun.misc.Unsafe
import java.io.File
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

    @Test
    @Ignore("Reproduces §1.2 zombie-handler race; LocalSocket cannot bind under Robolectric. Move to androidTest/ in a follow-up — covered by Task 6 stress harness in the meantime.")
    fun stopWaitsForInFlightHandlerToCompleteOverRealSocket(): Unit = runBlocking {
        // T2 drives the REAL socket path (not handleRequestForTest) so the
        // §1.2 zombie-handler race is actually reproduced. handleRequestForTest
        // bypasses acceptLoop.launch { handleClient(...) } entirely.
        val handlerEntered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val handlerFinished = CompletableDeferred<Unit>()
        val server = newServerWithBlockingEnvironment(handlerEntered, gate, handlerFinished)

        server.start()
        val socketName = (server.state.value as BridgeServerState.Running).socketName

        val clientJob = async(Dispatchers.IO) {
            val client = LocalSocket()
            client.connect(LocalSocketAddress(socketName))
            client.outputStream.bufferedWriter().also {
                it.write(statusRequestPayload(server.session.token))
                it.newLine()
                it.flush()
            }
            // Block on response; will not return until handler completes.
            client.inputStream.bufferedReader().readLine()
        }

        // Wait until handler is actually inside status(); only then call stop().
        withTimeout(5.seconds) { handlerEntered.await() }
        val stopJob = async { server.stop() }
        // Give stop() a chance to enter Stopping but not return.
        delay(50)
        assertEquals("stop() must wait for handler", false, stopJob.isCompleted)
        gate.complete(Unit) // release the handler

        withTimeout(5.seconds) { stopJob.await() }
        assertEquals("handler must finish before stop returns", true, handlerFinished.isCompleted)
        clientJob.await()
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
        yield()              // let collector observe initial Idle before start() overwrites it

        server.start()
        yield()              // let collector observe Running before stop() overwrites it
        server.stop()
        delay(50)            // allow trailing emission to land
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

    private fun fakeServerSocket(): LocalServerSocket =
        unsafe.allocateInstance(LocalServerSocket::class.java) as LocalServerSocket

    private fun newServerWithBlockingEnvironment(
        entered: CompletableDeferred<Unit>,
        gate: CompletableDeferred<Unit>,
        finished: CompletableDeferred<Unit>,
    ): BridgeServer {
        val session = fixedSession()
        val env = object : BridgeEnvironment by StubBridgeEnvironment() {
            override suspend fun status(): BridgeStatus {
                entered.complete(Unit)   // signal observation
                gate.await()              // hold until caller releases
                return BridgeStatus(
                    activity = null,
                    rootsCount = 0,
                    sidekickVersion = "test",
                    bridgeProtocolVersion = BridgeProtocol.VERSION,
                    sourceIndexAvailable = false,
                ).also { finished.complete(Unit) }
            }
        }
        return BridgeServer(session = session, environment = env)
    }

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
            Files.createTempDirectory("fixthis-cache").toFile().also { it.deleteOnExit() },
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
