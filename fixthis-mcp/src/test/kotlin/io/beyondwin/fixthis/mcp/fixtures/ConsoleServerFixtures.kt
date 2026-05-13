package io.beyondwin.fixthis.mcp.fixtures

import io.beyondwin.fixthis.cli.AdbDevice
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.console.ConsoleTokenHeader
import io.beyondwin.fixthis.mcp.session.AnnotationDto
import io.beyondwin.fixthis.mcp.session.AnnotationTargetDto
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import io.beyondwin.fixthis.mcp.tools.FixThisBridge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal const val PACKAGE_NAME = "io.beyondwin.fixthis.sample"

internal fun consoleTokenFrom(html: String): String = Regex("consoleToken:\\s*\"([^\"]+)\"")
    .find(html)
    ?.groupValues
    ?.get(1)
    ?: throw AssertionError("Expected served console HTML to include consoleToken config")

internal fun rawHttpResponseCode(
    baseUrl: String,
    method: String,
    path: String,
    headers: Map<String, String>,
): Int {
    val uri = URI.create(baseUrl)
    java.net.Socket(uri.host, uri.port).use { socket ->
        val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        writer.write("$method $path HTTP/1.1\r\n")
        writer.write("Host: ${uri.host}:${uri.port}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("Content-Length: 0\r\n")
        headers.forEach { (name, value) -> writer.write("$name: $value\r\n") }
        writer.write("\r\n")
        writer.flush()
        val statusLine = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
        return statusLine.split(" ")[1].toInt()
    }
}

internal fun javascriptFunctionBody(html: String, functionName: String): String {
    val declarationStart = html.indexOf("function $functionName(")
    assertTrue(declarationStart >= 0, "Missing JavaScript function: $functionName")

    val parametersEnd = html.indexOf(')', declarationStart)
    assertTrue(parametersEnd >= 0, "Missing JavaScript function parameter list: $functionName")

    val bodyStart = html.indexOf('{', parametersEnd)
    assertTrue(bodyStart >= 0, "Missing JavaScript function body: $functionName")

    var depth = 1
    for (index in bodyStart + 1 until html.length) {
        when (html[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return html.substring(bodyStart + 1, index)
            }
        }
    }

    throw AssertionError("Unclosed JavaScript function body: $functionName")
}

internal fun assertDoesNotClearDraftOrPreview(functionName: String, body: String) {
    assertFalse(
        body.contains("pendingFeedbackItems = [];"),
        "$functionName should not clear pending feedback items",
    )
    assertFalse(
        body.contains("addItemsFlow = null"),
        "$functionName should not clear an active add-items flow",
    )
    assertFalse(
        body.contains("state.preview = null"),
        "$functionName should not clear the current preview",
    )
    assertFalse(
        body.contains("invalidatePreviewContext()"),
        "$functionName should not invalidate preview context",
    )
}

internal fun writeConsoleAssets(directory: File, marker: String) {
    File(directory, "index.html").writeText(
        """
        <html>
          <head><!-- FIXTHIS_STYLES --></head>
          <body>$marker<!-- FIXTHIS_SCRIPT --></body>
        </html>
        """.trimIndent(),
    )
    File(directory, "styles.css").writeText("body { --marker: '$marker'; }")
    File(directory, "app.js").writeText("window.fixThisMarker = '$marker';")
}

internal class FakeIds(vararg values: String) {
    private val queue = ArrayDeque(values.toList())
    val next: () -> String = { queue.removeFirst() }
}

internal class FakeLongs(vararg values: Long) {
    private val queue = ArrayDeque(values.toList())
    val next: () -> Long = { queue.removeFirst() }
}

internal fun FeedbackSessionService.captureFakeScreenForTest(
    sessionId: String,
): SnapshotDto = runBlocking { captureScreen(sessionId) }

internal fun FeedbackSessionService.addCapturedScreenForTest(
    sessionId: String,
    screen: SnapshotDto,
): SnapshotDto = javaClass.getDeclaredField("store").let { field ->
    field.isAccessible = true
    (field.get(this) as FeedbackSessionStore).addScreen(sessionId, screen)
}

internal class DeviceListBridge(private val devices: List<AdbDevice>) : FixThisBridge {
    var selectedDeviceSerial: String? = null
        private set

    override fun resolvePackageName(packageOverride: String?): String = packageOverride ?: PACKAGE_NAME

    override fun devices(): List<AdbDevice> = devices

    override fun selectedDeviceSerial(): String? = selectedDeviceSerial

    override fun selectDevice(serial: String) {
        selectedDeviceSerial = serial
    }

    override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun verifyUiChange(
        packageName: String,
        expectedText: String,
        role: String?,
    ): JsonObject = JsonObject(emptyMap())

    override suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String?,
        screenId: String?,
        destinationDirectory: File?,
    ): JsonObject = JsonObject(emptyMap())
}

internal class SessionScreenshotBridge(private val pngBytes: ByteArray) : FixThisBridge {
    override fun resolvePackageName(packageOverride: String?): String = packageOverride ?: PACKAGE_NAME

    override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun verifyUiChange(
        packageName: String,
        expectedText: String,
        role: String?,
    ): JsonObject = JsonObject(emptyMap())

    override suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String?,
        screenId: String?,
        destinationDirectory: File?,
    ): JsonObject = buildJsonObject {
        val artifact = requireNotNull(destinationDirectory)
            .resolve("${requireNotNull(screenId)}-full.png")
        artifact.parentFile.mkdirs()
        artifact.writeBytes(pngBytes)
        put("activity", "MainActivity")
        put("sourceIndexAvailable", true)
        put(
            "inspection",
            buildJsonObject {
                put("activity", "MainActivity")
                put("roots", JsonArray(emptyList()))
                put("errors", JsonArray(emptyList()))
            },
        )
        put(
            "screenshot",
            buildJsonObject {
                put("desktopFullPath", artifact.absolutePath)
            },
        )
    }
}

internal class SequencedSessionScreenshotBridge(vararg pngBytes: ByteArray) : FixThisBridge {
    private val queue = ArrayDeque(pngBytes.toList())

    override fun resolvePackageName(packageOverride: String?): String = packageOverride ?: PACKAGE_NAME

    override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun verifyUiChange(
        packageName: String,
        expectedText: String,
        role: String?,
    ): JsonObject = JsonObject(emptyMap())

    override suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String?,
        screenId: String?,
        destinationDirectory: File?,
    ): JsonObject = buildJsonObject {
        val artifact = requireNotNull(destinationDirectory)
            .resolve("${requireNotNull(screenId)}-full.png")
        artifact.parentFile.mkdirs()
        artifact.writeBytes(queue.removeFirst())
        put("activity", "MainActivity")
        put("sourceIndexAvailable", true)
        put(
            "inspection",
            buildJsonObject {
                put("activity", "MainActivity")
                put("roots", JsonArray(emptyList()))
                put("errors", JsonArray(emptyList()))
            },
        )
        put(
            "screenshot",
            buildJsonObject {
                put("desktopFullPath", artifact.absolutePath)
            },
        )
    }
}

internal class SequencedFingerprintBridge(vararg fingerprints: String) : FixThisBridge {
    private val queue = ArrayDeque(fingerprints.toList())

    override fun resolvePackageName(packageOverride: String?): String = packageOverride ?: PACKAGE_NAME

    override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun verifyUiChange(
        packageName: String,
        expectedText: String,
        role: String?,
    ): JsonObject = JsonObject(emptyMap())

    override suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String?,
        screenId: String?,
        destinationDirectory: File?,
    ): JsonObject = buildJsonObject {
        val artifact = requireNotNull(destinationDirectory)
            .resolve("${requireNotNull(screenId)}-full.png")
        artifact.parentFile.mkdirs()
        artifact.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
        put("activity", "MainActivity")
        put("fingerprint", queue.removeFirst())
        put("sourceIndexAvailable", true)
        put(
            "inspection",
            buildJsonObject {
                put("activity", "MainActivity")
                put("roots", JsonArray(emptyList()))
                put("errors", JsonArray(emptyList()))
            },
        )
        put(
            "screenshot",
            buildJsonObject {
                put("desktopFullPath", artifact.absolutePath)
            },
        )
    }
}

internal class NullableSequencedFingerprintBridge(vararg fingerprints: String?) : FixThisBridge {
    private val queue = ArrayDeque(fingerprints.toList())

    override fun resolvePackageName(packageOverride: String?): String = packageOverride ?: PACKAGE_NAME

    override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun verifyUiChange(
        packageName: String,
        expectedText: String,
        role: String?,
    ): JsonObject = JsonObject(emptyMap())

    override suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String?,
        screenId: String?,
        destinationDirectory: File?,
    ): JsonObject = buildJsonObject {
        val artifact = requireNotNull(destinationDirectory)
            .resolve("${requireNotNull(screenId)}-full.png")
        artifact.parentFile.mkdirs()
        artifact.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
        put("activity", "MainActivity")
        queue.removeFirst()?.let { put("fingerprint", it) }
        put("sourceIndexAvailable", true)
        put(
            "inspection",
            buildJsonObject {
                put("activity", "MainActivity")
                put("roots", JsonArray(emptyList()))
                put("errors", JsonArray(emptyList()))
            },
        )
        put(
            "screenshot",
            buildJsonObject {
                put("desktopFullPath", artifact.absolutePath)
            },
        )
    }
}

internal class SecondCaptureIllegalArgumentBridge : FixThisBridge {
    private var captureCount = 0

    override fun resolvePackageName(packageOverride: String?): String = packageOverride ?: PACKAGE_NAME

    override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun verifyUiChange(
        packageName: String,
        expectedText: String,
        role: String?,
    ): JsonObject = JsonObject(emptyMap())

    override suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String?,
        screenId: String?,
        destinationDirectory: File?,
    ): JsonObject {
        captureCount += 1
        require(captureCount != 2) { "recapture failed" }
        return buildJsonObject {
            val artifact = requireNotNull(destinationDirectory)
                .resolve("${requireNotNull(screenId)}-full.png")
            artifact.parentFile.mkdirs()
            artifact.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
            put("activity", "MainActivity")
            put("fingerprint", "frozen")
            put("sourceIndexAvailable", true)
            put(
                "inspection",
                buildJsonObject {
                    put("activity", "MainActivity")
                    put("roots", JsonArray(emptyList()))
                    put("errors", JsonArray(emptyList()))
                },
            )
            put(
                "screenshot",
                buildJsonObject {
                    put("desktopFullPath", artifact.absolutePath)
                },
            )
        }
    }
}

internal class BlockingCaptureBridge(
    private val previewStarted: CountDownLatch,
    private val releasePreview: CountDownLatch,
) : FixThisBridge {
    override fun resolvePackageName(packageOverride: String?): String = packageOverride ?: PACKAGE_NAME

    override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun verifyUiChange(
        packageName: String,
        expectedText: String,
        role: String?,
    ): JsonObject = JsonObject(emptyMap())

    override suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String?,
        screenId: String?,
        destinationDirectory: File?,
    ): JsonObject = buildJsonObject {
        previewStarted.countDown()
        releasePreview.await(5, TimeUnit.SECONDS)
        put("activity", "MainActivity")
        put("sourceIndexAvailable", true)
        put(
            "inspection",
            buildJsonObject {
                put("activity", "MainActivity")
                put("roots", JsonArray(emptyList()))
                put("errors", JsonArray(emptyList()))
            },
        )
        put(
            "screenshot",
            buildJsonObject {
                put("width", 720)
                put("height", 1600)
            },
        )
    }
}

internal class LegacyScreenshotBridge(private val artifact: File) : FixThisBridge {
    override fun resolvePackageName(packageOverride: String?): String = packageOverride ?: PACKAGE_NAME

    override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun verifyUiChange(
        packageName: String,
        expectedText: String,
        role: String?,
    ): JsonObject = JsonObject(emptyMap())

    override suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String?,
        screenId: String?,
        destinationDirectory: File?,
    ): JsonObject = buildJsonObject {
        put("activity", "MainActivity")
        put("sourceIndexAvailable", true)
        put(
            "inspection",
            buildJsonObject {
                put("activity", "MainActivity")
                put("roots", JsonArray(emptyList()))
                put("errors", JsonArray(emptyList()))
            },
        )
        put(
            "screenshot",
            buildJsonObject {
                put("desktopFullPath", artifact.absolutePath)
            },
        )
    }
}

internal class ConsoleHttpTestClient(
    private val baseUrl: String,
    private val includeConsoleToken: Boolean = true,
) {
    private val consoleToken: String? by lazy {
        if (!includeConsoleToken) return@lazy null
        Regex("consoleToken:\\s*\"([^\"]+)\"")
            .find(java.net.URI(baseUrl).toURL().readText())
            ?.groupValues
            ?.get(1)
    }

    fun get(path: String = "/"): String = java.net.URI(baseUrl + path).toURL().readText()

    fun getResponse(path: String, headers: Map<String, String> = emptyMap()): ConsoleHttpResponse {
        val connection = java.net.URI(baseUrl + path).toURL().openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "GET"
        if (path.startsWith("/api/")) {
            consoleToken?.let { connection.setRequestProperty(ConsoleTokenHeader, it) }
        }
        for ((name, value) in headers) {
            connection.setRequestProperty(name, value)
        }
        val statusCode = connection.responseCode
        val body = runCatching {
            (connection.errorStream ?: connection.inputStream)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: ""
        }.getOrDefault("")
        val headerFields: Map<String, List<String>> = connection.headerFields
            .filterKeys { it != null }
            .mapKeys { (key, _) -> key!! }
        return ConsoleHttpResponse(statusCode = statusCode, body = body, headers = headerFields)
    }

    fun connection(path: String, method: String = "GET", body: String? = null): java.net.HttpURLConnection {
        val connection = java.net.URI(baseUrl + path).toURL().openConnection() as java.net.HttpURLConnection
        connection.requestMethod = method
        if (path.startsWith("/api/")) {
            consoleToken?.let { connection.setRequestProperty(ConsoleTokenHeader, it) }
        }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return connection
    }

    fun postJson(path: String, body: String): ConsoleHttpResponse {
        val conn = java.net.URI(baseUrl + path).toURL().openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (path.startsWith("/api/")) {
            consoleToken?.let { conn.setRequestProperty(ConsoleTokenHeader, it) }
        }
        conn.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
        val statusCode = conn.responseCode
        val responseBody = runCatching {
            (conn.errorStream ?: conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        }.getOrDefault("")
        val headerFields: Map<String, List<String>> = conn.headerFields
            .filterKeys { it != null }
            .mapKeys { (key, _) -> key!! }
        return ConsoleHttpResponse(statusCode = statusCode, body = responseBody, headers = headerFields)
    }
}

internal data class ConsoleHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>>,
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()

    fun contentTypeStartsWith(prefix: String): Boolean = header("Content-Type")?.startsWith(prefix) == true
}

internal fun seedSessionWithOneItem(
    store: FeedbackSessionStore,
    service: FeedbackSessionService,
): Pair<String, String> {
    val session = service.openSession(null, newSession = true)
    store.addScreen(
        session.sessionId,
        SnapshotDto(
            screenId = "screen-1",
            capturedAtEpochMillis = 100L,
            displayName = "Screen 1",
        ),
    )
    val item = store.addItem(
        session.sessionId,
        AnnotationDto(
            itemId = "pending",
            screenId = "screen-1",
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
            comment = "Test feedback",
        ),
    )
    return Pair(session.sessionId, item.itemId)
}
