package io.beyondwin.fixthis.cli

import io.beyondwin.fixthis.cli.bridge.ActiveBridgeRequest
import io.beyondwin.fixthis.cli.bridge.AdbForwardingBridgeTransport
import io.beyondwin.fixthis.cli.bridge.BridgeProtocolClient
import io.beyondwin.fixthis.cli.bridge.BridgeRequestScope
import io.beyondwin.fixthis.cli.bridge.BridgeSessionReader
import io.beyondwin.fixthis.cli.bridge.DeviceSelectionState
import io.beyondwin.fixthis.cli.bridge.ScreenshotArtifactDownloader
import io.beyondwin.fixthis.cli.bridge.ScreenshotArtifactRequest
import io.beyondwin.fixthis.cli.bridge.SidekickSession
import io.beyondwin.fixthis.cli.bridge.sanitizedPathSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Mirrors BridgeProtocol.VERSION (fixthis-compose-sidekick), the constant in
// ServerVersionRoutes.kt (fixthis-mcp), and MinimumSupportedProtocolVersion in
// staleness.js. BridgeProtocolVersionSyncTest (`:fixthis-mcp:test`) fails if any
// of the 4 sites lag — bump them all together. See docs/reference/bridge-protocol.md.
private const val BridgeProtocolVersion = "1.3"
private const val DefaultSocketTimeoutMillis = 30_000
private const val BRIDGE_SOCKET_NAME_MAX_ATTEMPTS = 3
private const val BRIDGE_CLOSED_BEFORE_RESPONSE = "Bridge closed before sending a response"

@OptIn(ExperimentalSerializationApi::class)
val fixThisJson: Json = Json {
    prettyPrint = true
    explicitNulls = false
    encodeDefaults = true
    ignoreUnknownKeys = true
}

class BridgeClient(
    private val adb: AdbFacade? = null,
    private val projectRoot: File = File("."),
    private val portAllocator: () -> Int = { allocateLocalPort() },
    private val socketTimeoutMillis: Int = DefaultSocketTimeoutMillis,
    private val socketConnector: (Int) -> BridgeSocket = { port -> TcpBridgeSocket(port, socketTimeoutMillis) },
) {
    private val resolvedAdb: AdbFacade by lazy { adb ?: Adb.forProject(projectRoot) }
    private val deviceSelection = DeviceSelectionState()
    private val sessionReader = BridgeSessionReader(expectedProtocolVersion = BridgeProtocolVersion)
    private val protocolClient = BridgeProtocolClient(expectedProtocolVersion = BridgeProtocolVersion)
    private val transport = AdbForwardingBridgeTransport(portAllocator, socketConnector)
    private val artifactDownloader = ScreenshotArtifactDownloader { scope, packageName, method, params ->
        requestInScope(scope = scope, packageName = packageName, method = method, params = params)
    }

    fun devices(): List<AdbDevice> = resolvedAdb.devices()

    fun selectDevice(serial: String) {
        deviceSelection.select(serial)
    }

    fun disconnectDevice() {
        deviceSelection.clear()
    }

    fun selectedDeviceSerial(): String? = deviceSelection.selected()

    private fun requestScope(): BridgeRequestScope {
        val serial = deviceSelection.selected()
        return BridgeRequestScope(selectedDeviceSerial = serial, adb = resolvedAdb.forDevice(serial))
    }

    suspend fun request(
        packageName: String,
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        readTimeoutMillis: Long = socketTimeoutMillis.toLong(),
    ): JsonObject = requestInScope(requestScope(), packageName, method, params, readTimeoutMillis)

    private suspend fun requestInScope(
        scope: BridgeRequestScope,
        packageName: String,
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        readTimeoutMillis: Long = socketTimeoutMillis.toLong(),
    ): JsonObject = suspendCancellableCoroutine { continuation ->
        val activeRequest = ActiveBridgeRequest(scope.adb)
        val worker = thread(name = "fixthis-bridge-request", isDaemon = true) {
            try {
                val result = executeRequest(packageName, method, params, readTimeoutMillis, scope, activeRequest)
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation {
            activeRequest.cancel()
            worker.interrupt()
        }
    }

    private fun executeRequest(
        packageName: String,
        method: String,
        params: JsonObject,
        readTimeoutMillis: Long,
        scope: BridgeRequestScope,
        activeRequest: ActiveBridgeRequest,
    ): JsonObject {
        ensureDeviceConnected(scope.adb, scope.selectedDeviceSerial)
        val session = sessionReader.read(scope.adb, packageName)
        for (attempt in 0 until BRIDGE_SOCKET_NAME_MAX_ATTEMPTS) {
            val attemptedSession = session.withSocketNameAttempt(attempt)
            try {
                return transport.withSocket(scope.adb, attemptedSession, activeRequest) { socket ->
                    protocolClient.request(socket, attemptedSession, method, params, readTimeoutMillis)
                }
            } catch (error: BridgeConnectionException) {
                activeRequest.throwIfCancelled()
                if (!error.isClosedBeforeResponse() || attempt == BRIDGE_SOCKET_NAME_MAX_ATTEMPTS - 1) throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: SocketTimeoutException) {
                throw BridgeConnectionException("Bridge request timed out while waiting for $method response")
            } catch (error: IOException) {
                throw BridgeConnectionException("Could not connect to FixThis bridge: ${error.message}")
            }
        }
        throw BridgeConnectionException(BRIDGE_CLOSED_BEFORE_RESPONSE)
    }

    fun resolvePackageName(packageOverride: String?): String = ProjectConfig.resolvePackageName(projectRoot, packageOverride)

    suspend fun performNavigation(packageName: String, request: JsonObject): JsonObject = request(packageName = packageName, method = "performNavigation", params = request)

    suspend fun readSourceIndex(packageName: String): JsonObject = request(packageName = packageName, method = "readSourceIndex")

    fun launchApp(packageName: String) {
        val scope = requestScope()
        ensureDeviceConnected(scope.adb, scope.selectedDeviceSerial)
        scope.adb.launchApp(packageName)
    }

    suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String? = null,
        screenId: String? = null,
        destinationDirectory: File? = null,
    ): JsonObject {
        val scope = requestScope()
        val params = buildJsonObject {
            scope.adb.currentFocusOutput()?.let { focus ->
                put("currentFocusOutput", focus)
            }
        }
        val result = requestInScope(
            scope = scope,
            packageName = packageName,
            method = "captureScreenSnapshot",
            params = params,
        )
        val screenshot = result["screenshot"]?.jsonObject ?: return result
        val artifactId = (screenId ?: "screen-${System.currentTimeMillis()}").sanitizedPathSegment()
        val artifactDirectory = destinationDirectory ?: projectRoot.resolve(".fixthis/artifacts/$artifactId")
        check(artifactDirectory.exists() || artifactDirectory.mkdirs()) {
            "Could not create FixThis artifact directory: ${artifactDirectory.absolutePath}"
        }

        val fullDesktopPath = artifactDownloader.readScreenshotArtifact(
            scope = scope,
            artifact = ScreenshotArtifactRequest(
                packageName = packageName,
                kind = "full",
                androidPath = screenshot["fullPath"]?.jsonPrimitive?.contentOrNull,
                destination = artifactDirectory.resolve("$artifactId-full.png"),
                source = "screenSnapshot",
            ),
        )
        val rewrittenScreenshot = buildJsonObject {
            screenshot.forEach { (key, value) -> put(key, value) }
            fullDesktopPath?.let { put("desktopFullPath", it) }
        }
        return buildJsonObject {
            result.forEach { (key, value) -> put(key, value) }
            put("screenshot", rewrittenScreenshot)
        }
    }

    private fun ensureDeviceConnected(adb: AdbFacade, selectedDeviceSerial: String?) {
        val devices = adb.devices()
        if (selectedDeviceSerial == null) {
            if (devices.none { it.state == "device" }) {
                throw NoDeviceException("No connected Android device or emulator found")
            }
            return
        }
        val device = devices.firstOrNull { it.serial == selectedDeviceSerial }
            ?: throw NoDeviceException("Selected Android device is not connected: $selectedDeviceSerial")
        if (device.state != "device") {
            throw NoDeviceException("Selected Android device is not ready: $selectedDeviceSerial (${device.state})")
        }
    }
}

private fun SidekickSession.withSocketNameAttempt(attempt: Int): SidekickSession {
    if (attempt == 0) return this
    val candidate = "$socketName-$attempt"
    return copy(socketName = candidate, socketAddress = "localabstract:$candidate")
}

private fun BridgeConnectionException.isClosedBeforeResponse(): Boolean =
    message == BRIDGE_CLOSED_BEFORE_RESPONSE

interface BridgeSocket : Closeable {
    val input: InputStream
    val output: OutputStream
    var readTimeoutMillis: Int
}

class NoDeviceException(message: String) : RuntimeException(message)
class BridgeConnectionException(message: String) : RuntimeException(message)
class BridgeProtocolException(message: String) : RuntimeException(message)

class BridgeRequestException(
    val code: String,
    val bridgeMessage: String,
) : RuntimeException("$code: $bridgeMessage")

private class TcpBridgeSocket(port: Int, timeoutMillis: Int) : BridgeSocket {
    private val socket = Socket().apply {
        connect(InetSocketAddress("127.0.0.1", port), timeoutMillis)
        soTimeout = timeoutMillis
    }
    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()
    override var readTimeoutMillis: Int
        get() = socket.soTimeout
        set(value) {
            socket.soTimeout = value
        }

    override fun close() {
        socket.close()
    }
}

private fun allocateLocalPort(): Int = ServerSocket(0).use { socket -> socket.localPort }
