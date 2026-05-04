package io.github.pointpatch.cli

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

private const val BridgeProtocolVersion = "1.0"
private const val SessionPath = "files/pointpatch/session.json"
private const val MaxFrameBytes = 16 * 1024 * 1024
private const val DefaultSocketTimeoutMillis = 30_000
private const val FeedbackCaptureTimeoutPaddingMillis = 5_000L

@OptIn(ExperimentalSerializationApi::class)
val pointPatchJson: Json = Json {
    prettyPrint = true
    explicitNulls = false
    encodeDefaults = true
    ignoreUnknownKeys = true
}

object ProjectConfig {
    fun resolvePackageName(projectRoot: File, packageOverride: String?): String {
        packageOverride?.takeIf { it.isNotBlank() }?.let { return it }
        val projectConfig = projectRoot.resolve(".pointpatch/project.json")
        require(projectConfig.exists()) {
            "No package was provided and ${projectConfig.path} does not exist"
        }
        val config = pointPatchJson.decodeFromString(ProjectMetadata.serializer(), projectConfig.readText())
        return config.applicationId.takeIf { it.isNotBlank() }
            ?: error("${projectConfig.path} does not contain applicationId")
    }
}

class BridgeClient(
    private val adb: AdbFacade = Adb(),
    private val projectRoot: File = File("."),
    private val portAllocator: () -> Int = { allocateLocalPort() },
    private val socketTimeoutMillis: Int = DefaultSocketTimeoutMillis,
    private val socketConnector: (Int) -> BridgeSocket = { port -> TcpBridgeSocket(port, socketTimeoutMillis) },
) {
    private val requestIds = AtomicInteger(0)

    suspend fun request(
        packageName: String,
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        readTimeoutMillis: Long = socketTimeoutMillis.toLong(),
    ): JsonObject {
        ensureDeviceConnected()
        val session = readSession(packageName)
        validateProtocol(session.bridgeProtocolVersion)
        val localPort = portAllocator()
        adb.forward(localPort, session.socketAddress)

        return try {
            socketConnector(localPort).use { socket ->
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
                    pointPatchJson.encodeToString(BridgeRequest.serializer(), request),
                )
                val responsePayload = BridgeFrames.readFrame(socket.input)
                    ?: throw BridgeConnectionException("Bridge closed before sending a response")
                val response = pointPatchJson.decodeFromString(BridgeResponse.serializer(), responsePayload)
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
        } catch (error: SocketTimeoutException) {
            throw BridgeConnectionException("Bridge request timed out while waiting for $method response")
        } catch (error: IOException) {
            throw BridgeConnectionException("Could not connect to PointPatch bridge on tcp:$localPort: ${error.message}")
        } finally {
            runCatching { adb.removeForward(localPort) }
        }
    }

    fun resolvePackageName(packageOverride: String?): String =
        ProjectConfig.resolvePackageName(projectRoot, packageOverride)

    suspend fun pullArtifacts(packageName: String, annotation: JsonObject): JsonObject {
        val annotationId = annotation["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return annotation
        val screenshot = annotation["screenshot"]?.jsonObject ?: return annotation
        val artifactId = annotationId.sanitizedPathSegment()
        val artifactDirectory = projectRoot.resolve(".pointpatch/artifacts/$artifactId")
        check(artifactDirectory.exists() || artifactDirectory.mkdirs()) {
            "Could not create PointPatch artifact directory: ${artifactDirectory.absolutePath}"
        }

        val fullDesktopPath = readScreenshotArtifact(
            packageName = packageName,
            kind = "full",
            androidPath = screenshot["fullPath"]?.jsonPrimitive?.contentOrNull,
            destination = artifactDirectory.resolve("$artifactId-full.png"),
        )
        val cropDesktopPath = readScreenshotArtifact(
            packageName = packageName,
            kind = "crop",
            androidPath = screenshot["cropPath"]?.jsonPrimitive?.contentOrNull,
            destination = artifactDirectory.resolve("$artifactId-crop.png"),
        )

        val rewrittenScreenshot = buildJsonObject {
            screenshot.forEach { (key, value) -> put(key, value) }
            fullDesktopPath?.let { put("desktopFullPath", it) }
            cropDesktopPath?.let { put("desktopCropPath", it) }
        }
        return buildJsonObject {
            annotation.forEach { (key, value) -> put(key, value) }
            put("screenshot", rewrittenScreenshot)
        }
    }

    suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject {
        val result = request(
            packageName = packageName,
            method = "startFeedbackCapture",
            params = buildJsonObject {
                put("timeoutMillis", timeoutMillis)
            },
            readTimeoutMillis = timeoutMillis.saturatingPlus(FeedbackCaptureTimeoutPaddingMillis),
        )
        val annotation = result["annotation"]?.jsonObject ?: return result
        val rewrittenAnnotation = pullArtifacts(packageName, annotation)
        return buildJsonObject {
            result.forEach { (key, value) -> put(key, value) }
            put("annotation", rewrittenAnnotation)
        }
    }

    private fun ensureDeviceConnected() {
        if (adb.devices().isEmpty()) {
            throw NoDeviceException("No connected Android device or emulator found")
        }
    }

    private fun readSession(packageName: String): SidekickSession =
        runCatching {
            pointPatchJson.decodeFromString(
                SidekickSession.serializer(),
                adb.runAsCat(packageName, SessionPath),
            )
        }.getOrElse { error ->
            throw BridgeConnectionException(
                "Could not read sidekick session via adb shell run-as $packageName cat $SessionPath: ${error.message}",
            )
        }

    private fun validateProtocol(protocolVersion: String) {
        if (protocolVersion != BridgeProtocolVersion) {
            throw BridgeProtocolException(
                "Sidekick protocol $protocolVersion is incompatible with CLI protocol $BridgeProtocolVersion",
            )
        }
    }

    private suspend fun readScreenshotArtifact(
        packageName: String,
        kind: String,
        androidPath: String?,
        destination: File,
    ): String? {
        androidPath?.takeIf { it.isNotBlank() } ?: return null
        val result = request(
            packageName = packageName,
            method = "readScreenshot",
            params = buildJsonObject {
                put("kind", kind)
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

private fun allocateLocalPort(): Int =
    ServerSocket(0).use { socket -> socket.localPort }

private fun String.sanitizedPathSegment(): String =
    replace(Regex("[^A-Za-z0-9._-]"), "_")

private fun Long.saturatingPlus(value: Long): Long =
    if (this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value

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
