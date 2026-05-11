package io.beyondwin.fixthis.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Mirrors BridgeProtocol.VERSION (fixthis-compose-sidekick), the constant in
// ServerVersionRoutes.kt (fixthis-mcp), and MinimumSupportedProtocolVersion in
// staleness.js. BridgeProtocolVersionSyncTest (`:fixthis-mcp:test`) fails if any
// of the 4 sites lag — bump them all together. See docs/reference/bridge-protocol.md.
private const val BridgeProtocolVersion = "1.2"

// Mirrors BridgeSocketNameNegotiator.MaxAttempts (fixthis-compose-sidekick).
private const val BridgeSocketNameMaxAttempts = 3
private const val SessionPath = "files/fixthis/session.json"
private const val MaxFrameBytes = 16 * 1024 * 1024
private const val DefaultSocketTimeoutMillis = 30_000

@OptIn(ExperimentalSerializationApi::class)
val fixThisJson: Json = Json {
    prettyPrint = true
    explicitNulls = false
    encodeDefaults = true
    ignoreUnknownKeys = true
}

object ProjectConfig {
    fun resolvePackageName(projectRoot: File, packageOverride: String?): String {
        packageOverride?.takeIf { it.isNotBlank() }?.let { return it }
        val projectConfig = projectRoot.resolve(".fixthis/project.json")
        require(projectConfig.exists()) {
            "No package was provided and ${projectConfig.path} does not exist"
        }
        val config = fixThisJson.decodeFromString(ProjectMetadata.serializer(), projectConfig.readText())
        return config.applicationId.takeIf { it.isNotBlank() }
            ?: error("${projectConfig.path} does not contain applicationId")
    }
}

class BridgeClient(
    private val adb: AdbFacade? = null,
    private val projectRoot: File = File("."),
    private val portAllocator: () -> Int = { allocateLocalPort() },
    private val socketTimeoutMillis: Int = DefaultSocketTimeoutMillis,
    private val socketConnector: (Int) -> BridgeSocket = { port -> TcpBridgeSocket(port, socketTimeoutMillis) },
) {
    private val requestIds = AtomicInteger(0)
    private val resolvedAdb: AdbFacade by lazy { adb ?: Adb.forProject(projectRoot) }

    @Volatile
    private var selectedDeviceSerial: String? = null

    fun devices(): List<AdbDevice> = resolvedAdb.devices()

    fun selectDevice(serial: String) {
        require(serial.isNotBlank()) { "Device serial must not be blank" }
        selectedDeviceSerial = serial
    }

    fun disconnectDevice() {
        selectedDeviceSerial = null
    }

    fun selectedDeviceSerial(): String? = selectedDeviceSerial

    private fun requestScope(): BridgeRequestScope {
        val serial = selectedDeviceSerial
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
        val worker = thread(
            name = "fixthis-bridge-request-${requestIds.get() + 1}",
            isDaemon = true,
        ) {
            try {
                val result = executeRequest(
                    packageName,
                    method,
                    params,
                    readTimeoutMillis,
                    scope,
                    activeRequest,
                )
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
        val session = readSidekickSession(scope.adb, packageName)
        validateProtocol(session.bridgeProtocolVersion)
        val localPort = portAllocator()
        activeRequest.registerForwardPort(localPort)

        return try {
            // Try the socket address baked into session.json first, then fall through
            // `<name>-1` / `<name>-2` to mirror BridgeSocketNameNegotiator on the
            // sidekick. FixThisBridgeRuntime rewrites session.json post-bind so the
            // primary address is usually correct; the suffixed fallbacks cover the
            // race where the CLI read session.json before the sidekick refreshed it.
            val socket = openSocketWithSocketNameFallback(
                adb = scope.adb,
                localPort = localPort,
                sessionSocketName = session.socketName,
                activeRequest = activeRequest,
            )
            activeRequest.throwIfCancelled()
            activeRequest.registerSocket(socket)
            activeRequest.throwIfCancelled()
            socket.use {
                socket.readTimeoutMillis = readTimeoutMillis
                    .coerceIn(1L, Int.MAX_VALUE.toLong())
                    .toInt()
                val request = BridgeRequest(
                    id = "req_${requestIds.incrementAndGet()}",
                    token = session.token,
                    method = method,
                    params = params,
                )
                BridgeFrames.writeFrame(
                    socket.output,
                    fixThisJson.encodeToString(BridgeRequest.serializer(), request),
                )
                val responsePayload = BridgeFrames.readFrame(socket.input)
                    ?: throw BridgeConnectionException("Bridge closed before sending a response")
                val response = fixThisJson.decodeFromString(BridgeResponse.serializer(), responsePayload)
                if (!response.ok) {
                    val error = response.error
                    throw BridgeRequestException(
                        code = error?.code ?: "BRIDGE_ERROR",
                        bridgeMessage = error?.message ?: "Bridge request failed",
                    )
                }
                val result = response.result?.jsonObject
                    ?: throw BridgeProtocolException("Bridge response did not include an object result")
                validateProtocol(result["bridgeProtocolVersion"]?.jsonPrimitive?.contentOrNull ?: BridgeProtocolVersion)
                result
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw BridgeConnectionException("Bridge request timed out while waiting for $method response")
        } catch (error: IOException) {
            throw BridgeConnectionException("Could not connect to FixThis bridge on tcp:$localPort: ${error.message}")
        } finally {
            activeRequest.cleanup()
        }
    }

    /**
     * Forward [localPort] to `localabstract:<sessionSocketName>` and open a
     * [BridgeSocket]; on [IOException] retry against `<name>-1` and `<name>-2`
     * to mirror the suffix retry on the sidekick side
     * (`BridgeSocketNameNegotiator`). [activeRequest] is marked established
     * once any candidate succeeds. Throws [BridgeConnectionException] if all
     * three candidates fail.
     */
    private fun openSocketWithSocketNameFallback(
        adb: AdbFacade,
        localPort: Int,
        sessionSocketName: String,
        activeRequest: ActiveBridgeRequest,
    ): BridgeSocket {
        var lastError: Throwable? = null
        for (attempt in 0 until BridgeSocketNameMaxAttempts) {
            val candidate = if (attempt == 0) sessionSocketName else "$sessionSocketName-$attempt"
            val address = "localabstract:$candidate"
            try {
                adb.forward(localPort, address)
            } catch (error: IOException) {
                lastError = error
                continue
            }
            // Any successful `adb forward` registers a forward we must clean up
            // even if the subsequent connect fails on this candidate.
            activeRequest.markForwardEstablished()
            val socket = try {
                socketConnector(localPort)
            } catch (error: IOException) {
                lastError = error
                continue
            }
            return socket
        }
        throw BridgeConnectionException(
            "Could not connect to FixThis bridge socket $sessionSocketName " +
                "(also tried $sessionSocketName-1, $sessionSocketName-2): " +
                (lastError?.message ?: "unknown error"),
        )
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
        val result = requestInScope(scope = scope, packageName = packageName, method = "captureScreenSnapshot")
        val screenshot = result["screenshot"]?.jsonObject ?: return result
        val artifactId = (screenId ?: "screen-${System.currentTimeMillis()}").sanitizedPathSegment()
        val artifactDirectory = destinationDirectory ?: projectRoot.resolve(".fixthis/artifacts/$artifactId")
        check(artifactDirectory.exists() || artifactDirectory.mkdirs()) {
            "Could not create FixThis artifact directory: ${artifactDirectory.absolutePath}"
        }

        val fullDesktopPath = readScreenshotArtifact(
            scope = scope,
            packageName = packageName,
            kind = "full",
            androidPath = screenshot["fullPath"]?.jsonPrimitive?.contentOrNull,
            destination = artifactDirectory.resolve("$artifactId-full.png"),
            source = "screenSnapshot",
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

    private fun readSidekickSession(adb: AdbFacade, packageName: String): SidekickSession = runCatching {
        fixThisJson.decodeFromString(
            SidekickSession.serializer(),
            adb.runAsCat(packageName, SessionPath),
        )
    }.getOrElse { error ->
        throw BridgeConnectionException(
            "Could not read FixThis bridge session via adb shell run-as $packageName cat $SessionPath: ${error.message}",
        )
    }

    private fun validateProtocol(protocolVersion: String) {
        if (protocolVersion != BridgeProtocolVersion) {
            throw BridgeProtocolException(
                "FixThis bridge protocol $protocolVersion is incompatible with CLI protocol $BridgeProtocolVersion",
            )
        }
    }

    private suspend fun readScreenshotArtifact(
        scope: BridgeRequestScope,
        packageName: String,
        kind: String,
        androidPath: String?,
        destination: File,
        source: String = "annotation",
    ): String? {
        androidPath?.takeIf { it.isNotBlank() } ?: return null
        val result = requestInScope(
            scope = scope,
            packageName = packageName,
            method = "readScreenshot",
            params = buildJsonObject {
                put("kind", kind)
                if (source != "annotation") {
                    put("source", source)
                }
            },
        )
        val mimeType = result["mimeType"]?.jsonPrimitive?.contentOrNull
        require(mimeType == "image/png") { "Bridge returned unsupported screenshot MIME type for $kind: $mimeType" }
        val base64 = result["base64"]?.jsonPrimitive?.contentOrNull
            ?: throw BridgeProtocolException("Bridge readScreenshot response omitted base64 for $kind")
        destination.writeBytes(Base64.getDecoder().decode(base64))
        return destination.absolutePath
    }
}

private data class BridgeRequestScope(
    val selectedDeviceSerial: String?,
    val adb: AdbFacade,
)

private class ActiveBridgeRequest(private val adb: AdbFacade) {
    private val lock = Object()
    private var localPort: Int? = null
    private var forwardEstablished = false
    private var forwardRemoved = false
    private var socket: BridgeSocket? = null
    private var cancelled = false

    fun registerForwardPort(port: Int) {
        synchronized(lock) {
            localPort = port
        }
    }

    fun markForwardEstablished() {
        val shouldRemove = synchronized(lock) {
            forwardEstablished = true
            cancelled
        }
        if (shouldRemove) removeForwardOnce()
    }

    fun registerSocket(socket: BridgeSocket) {
        val shouldClose = synchronized(lock) {
            this.socket = socket
            cancelled
        }
        if (shouldClose) runCatching { socket.close() }
    }

    fun cancel() {
        val socketToClose = synchronized(lock) {
            cancelled = true
            socket
        }
        runCatching { socketToClose?.close() }
        removeForwardOnce()
    }

    fun cleanup() {
        removeForwardOnce()
    }

    fun throwIfCancelled() {
        if (synchronized(lock) { cancelled }) {
            throw CancellationException("Bridge request cancelled")
        }
    }

    private fun removeForwardOnce() {
        val port = synchronized(lock) {
            if (!forwardEstablished || forwardRemoved) return
            val registeredPort = localPort ?: return
            forwardRemoved = true
            registeredPort
        }
        runCatching { adb.removeForward(port) }
    }
}

interface BridgeSocket : Closeable {
    val input: InputStream
    val output: OutputStream
    var readTimeoutMillis: Int
}

object BridgeFrames {
    fun writeFrame(output: OutputStream, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MaxFrameBytes) { "Bridge frame exceeds $MaxFrameBytes bytes" }
        output.write((bytes.size ushr 24) and 0xff)
        output.write((bytes.size ushr 16) and 0xff)
        output.write((bytes.size ushr 8) and 0xff)
        output.write(bytes.size and 0xff)
        output.write(bytes)
        output.flush()
    }

    fun readFrame(input: InputStream): String? {
        val first = input.read()
        if (first == -1) return null
        val length = (first shl 24) or
            (input.readRequiredByte() shl 16) or
            (input.readRequiredByte() shl 8) or
            input.readRequiredByte()
        require(length in 0..MaxFrameBytes) { "Invalid bridge frame length: $length" }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read == -1) throw EOFException("Unexpected EOF while reading bridge frame")
            offset += read
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun InputStream.readRequiredByte(): Int {
        val value = read()
        if (value == -1) throw EOFException("Unexpected EOF while reading bridge frame length")
        return value
    }
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

private fun String.sanitizedPathSegment(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")

@Serializable
private data class ProjectMetadata(
    val schemaVersion: String = "1.0",
    val applicationId: String,
)

@Serializable
private data class SidekickSession(
    val schemaVersion: String = "1.0",
    val packageName: String,
    val socketName: String,
    val socketAddress: String,
    val token: String,
    val sidekickVersion: String,
    val bridgeProtocolVersion: String,
    val createdAtEpochMillis: Long,
    val processStartEpochMillis: Long,
)

@Serializable
private data class BridgeRequest(
    val id: String,
    val token: String,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
)

@Serializable
private data class BridgeResponse(
    val id: String? = null,
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: BridgeError? = null,
)

@Serializable
private data class BridgeError(
    val code: String,
    val message: String,
)
