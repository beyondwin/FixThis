package io.beyondwin.fixthis.compose.sidekick.bridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import androidx.annotation.VisibleForTesting
import io.beyondwin.fixthis.compose.sidekick.bridge.handlers.defaultBridgeMethodHandlers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class BridgeServer(
    @VisibleForTesting
    internal val session: SidekickSession,
    private val environment: BridgeEnvironment,
    private val connectionState: BridgeConnectionState = BridgeConnectionState(),
    private val socketFactory: (String) -> LocalServerSocket = { socketName -> LocalServerSocket(socketName) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val screenshotReader = BridgeScreenshotReader(environment, ioDispatcher)
    private val router = BridgeRequestRouter(
        defaultBridgeMethodHandlers(
            environment = environment,
            connectionState = connectionState,
            screenshotReader = screenshotReader,
            socketNameProvider = { resolvedSocketName() ?: session.socketName },
        ),
    )

    private val _state = MutableStateFlow<BridgeServerState>(BridgeServerState.Idle)
    val state: StateFlow<BridgeServerState> = _state.asStateFlow()

    private val lifecycleMutex = Mutex()

    @Volatile
    private var serverSocket: LocalServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    fun resolvedSocketName(): String? = when (val s = _state.value) {
        is BridgeServerState.Running -> s.socketName
        else -> null
    }

    suspend fun start(): Boolean = lifecycleMutex.withLock {
        if (_state.value != BridgeServerState.Idle) return@withLock false
        _state.value = BridgeServerState.Starting
        val attempted = mutableListOf<String>()
        var lastError: Throwable? = null
        for (attempt in 0 until BridgeSocketNameNegotiator.MaxAttempts) {
            val candidate = BridgeSocketNameNegotiator.nextCandidate(session.socketName, attempt)
            attempted += candidate
            val socket = try {
                socketFactory(candidate)
            } catch (error: IOException) {
                lastError = error
                continue
            }
            serverSocket = socket
            acceptJob = scope.launch { acceptLoop(socket) }
            _state.value = BridgeServerState.Running(candidate)
            return@withLock true
        }
        _state.value = BridgeServerState.Idle
        Log.w(
            BridgeServerLogTag,
            "BridgeServer.start() failed after ${attempted.size} attempts: " +
                "tried ${attempted.joinToString(", ")}",
            lastError,
        )
        false
    }

    suspend fun stop() = lifecycleMutex.withLock {
        when (_state.value) {
            BridgeServerState.Idle, BridgeServerState.Stopping -> return@withLock
            else -> Unit
        }
        _state.value = BridgeServerState.Stopping
        runCatching { serverSocket?.close() }

        val drained = withTimeoutOrNull(StopDrainTimeout) {
            acceptJob?.cancelAndJoin()
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
        if (drained == null) {
            Log.w(
                BridgeServerLogTag,
                "BridgeServer.stop() drain timed out after $StopDrainTimeout; pending handlers leaked",
            )
        }

        serverSocket = null
        acceptJob = null
        _state.value = BridgeServerState.Idle
    }

    internal suspend fun handleRequestForTest(payload: String): String = handleRequest(payload)

    private suspend fun acceptLoop(socket: LocalServerSocket) {
        while (scope.isActive) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                break
            }
            scope.launch {
                try {
                    handleClient(client)
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    private suspend fun handleClient(client: LocalSocket) {
        val input = client.inputStream
        val output = client.outputStream
        while (scope.isActive) {
            val request = try {
                BridgeProtocol.readFrame(input) ?: return
            } catch (_: Throwable) {
                return
            }
            val response = handleRequest(request)
            BridgeProtocol.writeFrame(output, response)
        }
    }

    private suspend fun handleRequest(payload: String): String {
        val request = runCatching {
            BridgeProtocol.json.decodeFromString(BridgeRequest.serializer(), payload)
        }.getOrElse {
            return BridgeProtocol.error(id = null, code = "BAD_REQUEST", message = "Invalid bridge request JSON")
        }

        if (request.token != session.token) {
            return BridgeProtocol.error(request.id, "UNAUTHORIZED", "Missing or mismatched FixThis bridge token")
        }
        return try {
            val result = router.route(request.method, request.params)
                ?: return BridgeProtocol.error(request.id, "UNKNOWN_METHOD", "Unknown bridge method: ${request.method}")
            BridgeProtocol.success(request.id, result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            BridgeProtocol.error(
                id = request.id,
                code = "METHOD_FAILED",
                message = error.message ?: error::class.java.simpleName,
            )
        }
    }

    internal companion object {
        const val BridgeServerLogTag = "FixThisBridge"

        @VisibleForTesting
        internal val StopDrainTimeout: Duration = 5.seconds
    }
}
