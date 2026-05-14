package io.beyondwin.fixthis.compose.sidekick.bridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

class BridgeServer(
    // @VisibleForTesting: tests construct BridgeServer directly to drive the
    // socket path. Production callers go through FixThisBridgeRuntime which
    // only consumes resolvedSocketName(). Do not read this outside tests.
    @VisibleForTesting
    internal val session: SidekickSession,
    private val environment: BridgeEnvironment,
    private val connectionState: BridgeConnectionState = BridgeConnectionState(),
    private val socketFactory: (String) -> LocalServerSocket = { socketName -> LocalServerSocket(socketName) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val screenshotReader = BridgeScreenshotReader(environment, ioDispatcher)

    private val _state = MutableStateFlow<BridgeServerState>(BridgeServerState.Idle)
    val state: StateFlow<BridgeServerState> = _state.asStateFlow()

    @Volatile
    private var serverSocket: LocalServerSocket? = null

    /**
     * The actual socket name [start] bound to (may differ from the session's
     * configured name if a stale prior binding forced a suffix fallback;
     * see [BridgeSocketNameNegotiator]). `null` before [start] succeeds or
     * after [stop].
     */
    fun resolvedSocketName(): String? = when (val s = _state.value) {
        is BridgeServerState.Running -> s.socketName
        else -> null
    }

    fun start(): Boolean {
        if (serverSocket != null) return false
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
            _state.value = BridgeServerState.Running(candidate)
            scope.launch {
                acceptLoop(socket)
            }
            return true
        }
        _state.value = BridgeServerState.Idle
        Log.w(
            BridgeServerLogTag,
            "BridgeServer.start() failed after ${attempted.size} attempts: " +
                "tried ${attempted.joinToString(", ")}",
            lastError,
        )
        return false
    }

    fun stop() {
        _state.value = BridgeServerState.Stopping
        runCatching { serverSocket?.close() }
        serverSocket = null
        _state.value = BridgeServerState.Idle
        scope.cancel()
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
            val result = when (request.method) {
                "heartbeat" -> {
                    connectionState.markAuthorizedRequest()
                    BridgeProtocol.json.encodeToJsonElement(BridgeHeartbeatResult())
                }
                "status" -> BridgeProtocol.json.encodeToJsonElement(
                    environment.status().copy(socketName = resolvedSocketName() ?: session.socketName),
                )
                "inspectCurrentScreen" -> BridgeProtocol.json.encodeToJsonElement(environment.inspectCurrentScreen())
                "captureScreenSnapshot" -> BridgeProtocol.json.encodeToJsonElement(environment.captureScreenSnapshot())
                "readSourceIndex" -> BridgeProtocol.json.encodeToJsonElement(environment.readSourceIndex())
                "verifyUiChange" -> BridgeProtocol.json.encodeToJsonElement(verifyUiChange(request.params))
                "readScreenshot" -> BridgeProtocol.json.encodeToJsonElement(screenshotReader.read(request.params))
                "performNavigation" -> BridgeProtocol.json.encodeToJsonElement(
                    environment.performNavigation(
                        BridgeProtocol.json.decodeFromJsonElement(
                            BridgeNavigationRequest.serializer(),
                            request.params,
                        ),
                    ),
                )
                else -> return BridgeProtocol.error(request.id, "UNKNOWN_METHOD", "Unknown bridge method: ${request.method}")
            }
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

    private suspend fun verifyUiChange(params: JsonObject): BridgeUiVerificationResult {
        val expectedText = params.stringParam("expectedText")?.takeIf { it.isNotBlank() }
            ?: return BridgeUiVerificationResult(
                verified = false,
                message = "No expectedText parameter was provided",
            )
        val inspection = environment.inspectCurrentScreen()
        val matched = inspection.roots
            .flatMap { it.mergedNodes + it.unmergedNodes }
            .flatMap { node -> node.text + node.contentDescription }
            .firstOrNull { value -> value.contains(expectedText, ignoreCase = true) }
        return BridgeUiVerificationResult(
            verified = matched != null,
            expectedText = expectedText,
            matchedText = matched,
            message = if (matched == null) "Expected text was not found on the current screen" else null,
        )
    }

    private companion object {
        const val BridgeServerLogTag = "FixThisBridge"
    }
}

interface BridgeEnvironment {
    suspend fun status(): BridgeStatus
    suspend fun inspectCurrentScreen(): BridgeScreenInspection
    suspend fun captureScreenSnapshot(): BridgeScreenSnapshot
    suspend fun readSourceIndex(): BridgeSourceIndexResult
    suspend fun getLastScreenSnapshot(): BridgeScreenSnapshot?
    suspend fun performNavigation(request: BridgeNavigationRequest): BridgeNavigationResult
    fun screenshotCacheDirectory(): File
}

internal fun JsonObject.stringParam(name: String): String? = get(name)?.jsonPrimitive?.content
