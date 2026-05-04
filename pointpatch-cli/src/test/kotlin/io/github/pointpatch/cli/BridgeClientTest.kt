package io.github.pointpatch.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BridgeClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolvesPackageFromOverrideBeforeProjectConfig() {
        val root = temporaryFolder.newFolder()
        root.resolve(".pointpatch").mkdirs()
        root.resolve(".pointpatch/project.json").writeText(
            """{"schemaVersion":"1.0","applicationId":"io.github.pointpatch.fromfile"}""",
        )

        assertEquals(
            "io.github.pointpatch.override",
            ProjectConfig.resolvePackageName(root, "io.github.pointpatch.override"),
        )
    }

    @Test
    fun resolvesPackageFromProjectConfig() {
        val root = temporaryFolder.newFolder()
        root.resolve(".pointpatch").mkdirs()
        root.resolve(".pointpatch/project.json").writeText(
            """{"schemaVersion":"1.0","applicationId":"io.github.pointpatch.sample"}""",
        )

        assertEquals("io.github.pointpatch.sample", ProjectConfig.resolvePackageName(root, null))
    }

    @Test
    fun framesStatusRequestAndValidatesProtocolVersion() = runBlocking {
        val adb = FakeAdbFacade(
            sessionJson = sessionJson(protocol = "1.0"),
        )
        val socket = CapturingBridgeSocket(
            responsePayload = """
                {
                  "id": "req_1",
                  "ok": true,
                  "result": {
                    "activity": "MainActivity",
                    "rootsCount": 2,
                    "sidekickVersion": "0.1.0",
                    "bridgeProtocolVersion": "1.0",
                    "sourceIndexAvailable": true
                  }
                }
            """.trimIndent(),
        )
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 },
            socketConnector = { port ->
                assertEquals(34567, port)
                socket
            },
        )

        val result = client.request("io.github.pointpatch.sample", "status")

        assertEquals("MainActivity", result.getValue("activity").jsonPrimitive.content)
        assertEquals(
            listOf(34567 to "localabstract:pointpatch_io.github.pointpatch.sample"),
            adb.forwarded,
        )
        assertEquals(listOf(34567), adb.removedForwards)
        val request = Json.parseToJsonElement(readFrame(socket.written.toByteArray())).jsonObject
        assertEquals("req_1", request.getValue("id").jsonPrimitive.content)
        assertEquals("token-1", request.getValue("token").jsonPrimitive.content)
        assertEquals("status", request.getValue("method").jsonPrimitive.content)
    }

    @Test
    fun rejectsIncompatibleSessionProtocolBeforeForwarding() = runBlocking {
        val adb = FakeAdbFacade(sessionJson = sessionJson(protocol = "2.0"))
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 },
            socketConnector = { error("socket should not be opened") },
        )

        val error = kotlin.runCatching {
            client.request("io.github.pointpatch.sample", "status")
        }.exceptionOrNull()

        assertTrue(error is BridgeProtocolException)
        assertEquals(emptyList<Pair<Int, String>>(), adb.forwarded)
        assertEquals(emptyList<Int>(), adb.removedForwards)
    }

    @Test
    fun removesForwardWhenBridgeRequestFailsAfterForwarding() = runBlocking {
        val adb = FakeAdbFacade(sessionJson = sessionJson(protocol = "1.0"))
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 },
            socketConnector = { throw IOException("socket refused") },
        )

        val error = kotlin.runCatching {
            client.request("io.github.pointpatch.sample", "status")
        }.exceptionOrNull()

        assertTrue(error is BridgeConnectionException)
        assertEquals(
            listOf(34567 to "localabstract:pointpatch_io.github.pointpatch.sample"),
            adb.forwarded,
        )
        assertEquals(listOf(34567), adb.removedForwards)
    }

    @Test
    fun mapsSocketReadTimeoutToBridgeConnectionException() = runBlocking {
        val adb = FakeAdbFacade(sessionJson = sessionJson(protocol = "1.0"))
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 },
            socketConnector = { TimeoutBridgeSocket() },
        )

        val error = kotlin.runCatching {
            client.request("io.github.pointpatch.sample", "status")
        }.exceptionOrNull()

        assertTrue(error is BridgeConnectionException)
        assertTrue(error?.message.orEmpty().contains("timed out", ignoreCase = true))
        assertEquals(listOf(34567), adb.removedForwards)
    }

    @Test
    fun cancellationClosesActiveSocketAndRemovesForward() = runBlocking {
        val adb = FakeAdbFacade(sessionJson = sessionJson(protocol = "1.0"))
        val socket = BlockingBridgeSocket()
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 },
            socketConnector = { socket },
        )
        val job = launch(Dispatchers.IO) {
            client.request("io.github.pointpatch.sample", "status")
        }

        withTimeout(1_000) { socket.readStarted.await() }
        job.cancel()

        withTimeout(1_000) {
            socket.closed.await()
            while (adb.removedForwards != listOf(34567)) {
                delay(10)
            }
        }
        job.cancelAndJoin()
        assertEquals(listOf(34567), adb.removedForwards)
    }

    @Test
    fun readsScreenshotArtifactsThroughBridgeAndRewritesDesktopPaths() = runBlocking {
        val root = temporaryFolder.newFolder()
        val adb = FakeAdbFacade(sessionJson = sessionJson(protocol = "1.0"))
        val bridgeSockets =
            listOf(
                CapturingBridgeSocket(
                    responsePayload = """
                        {
                          "id": "req_1",
                          "ok": true,
                          "result": {
                            "submitted": true,
                            "timedOut": false,
                            "timeoutMillis": 120000,
                            "bridgeProtocolVersion": "1.0",
                            "annotation": {
                              "id": "ann-123",
                              "screenshot": {
                                "fullPath": "/data/user/0/io.github.pointpatch.sample/cache/pointpatch/ann-123-full.png",
                                "cropPath": "/data/user/0/io.github.pointpatch.sample/cache/pointpatch/ann-123-crop.png"
                              }
                            }
                          }
                        }
                    """.trimIndent(),
                ),
                CapturingBridgeSocket(
                    responsePayload = """
                        {
                          "id": "req_2",
                          "ok": true,
                          "result": {
                            "bridgeProtocolVersion": "1.0",
                            "path": "/data/user/0/io.github.pointpatch.sample/cache/pointpatch/ann-123-full.png",
                            "kind": "full",
                            "mimeType": "image/png",
                            "base64": "ZnVsbC1wbmc="
                          }
                        }
                    """.trimIndent(),
                ),
                CapturingBridgeSocket(
                    responsePayload = """
                        {
                          "id": "req_3",
                          "ok": true,
                          "result": {
                            "bridgeProtocolVersion": "1.0",
                            "path": "/data/user/0/io.github.pointpatch.sample/cache/pointpatch/ann-123-crop.png",
                            "kind": "crop",
                            "mimeType": "image/png",
                            "base64": "Y3JvcC1wbmc="
                          }
                        }
                    """.trimIndent(),
                ),
            )
        val sockets = ArrayDeque(bridgeSockets)
        val client = BridgeClient(
            adb = adb,
            projectRoot = root,
            portAllocator = { 34567 + adb.forwarded.size },
            socketConnector = { sockets.removeFirst() },
        )

        val result = client.startFeedbackCapture("io.github.pointpatch.sample", timeoutMillis = 120_000L)
        val screenshot = result.getValue("annotation").jsonObject.getValue("screenshot").jsonObject

        val fullDestination = root.resolve(".pointpatch/artifacts/ann-123/ann-123-full.png")
        val cropDestination = root.resolve(".pointpatch/artifacts/ann-123/ann-123-crop.png")
        assertEquals(fullDestination.absolutePath, screenshot.getValue("desktopFullPath").jsonPrimitive.content)
        assertEquals(cropDestination.absolutePath, screenshot.getValue("desktopCropPath").jsonPrimitive.content)
        assertEquals("full-png", fullDestination.readText())
        assertEquals("crop-png", cropDestination.readText())
        assertEquals(emptyList<Pair<String, File>>(), adb.pulled)
        assertEquals(listOf(34567, 34568, 34569), adb.removedForwards)
        assertEquals(
            listOf("startFeedbackCapture", "readScreenshot", "readScreenshot"),
            bridgeSockets.map { socket ->
                Json.parseToJsonElement(readFrame(socket.written.toByteArray()))
                    .jsonObject
                    .getValue("method")
                    .jsonPrimitive
                    .content
            },
        )
        assertEquals(
            listOf("full", "crop"),
            bridgeSockets.drop(1).map { socket ->
                Json.parseToJsonElement(readFrame(socket.written.toByteArray()))
                    .jsonObject
                    .getValue("params")
                    .jsonObject
                    .getValue("kind")
                    .jsonPrimitive
                    .content
            },
        )
    }

    @Test
    fun skipsMissingScreenshotKindsWhenAnnotationHasNoAndroidPaths() = runBlocking {
        val root = temporaryFolder.newFolder()
        val adb = FakeAdbFacade(sessionJson = sessionJson(protocol = "1.0"))
        val socket = CapturingBridgeSocket(
            responsePayload = """
                {
                  "id": "req_1",
                  "ok": true,
                  "result": {
                    "submitted": true,
                    "timedOut": false,
                    "timeoutMillis": 120000,
                    "bridgeProtocolVersion": "1.0",
                    "annotation": {
                      "id": "ann-123",
                      "screenshot": {}
                    }
                  }
                }
            """.trimIndent(),
        )
        val client = BridgeClient(
            adb = adb,
            projectRoot = root,
            portAllocator = { 34567 },
            socketConnector = { socket },
        )

        val result = client.startFeedbackCapture("io.github.pointpatch.sample", timeoutMillis = 120_000L)
        val screenshot = result.getValue("annotation").jsonObject.getValue("screenshot").jsonObject

        assertTrue("desktopFullPath" !in screenshot)
        assertTrue("desktopCropPath" !in screenshot)
        assertEquals(emptyList<Pair<String, File>>(), adb.pulled)
    }

    private fun sessionJson(protocol: String): String =
        """
            {
              "schemaVersion": "1.0",
              "packageName": "io.github.pointpatch.sample",
              "socketName": "pointpatch_io.github.pointpatch.sample",
              "socketAddress": "localabstract:pointpatch_io.github.pointpatch.sample",
              "token": "token-1",
              "sidekickVersion": "0.1.0",
              "bridgeProtocolVersion": "$protocol",
              "createdAtEpochMillis": 1,
              "processStartEpochMillis": 1
            }
        """.trimIndent()

    private fun readFrame(bytes: ByteArray): String {
        val input = ByteArrayInputStream(bytes)
        val length = (input.read() shl 24) or (input.read() shl 16) or (input.read() shl 8) or input.read()
        return input.readNBytes(length).toString(Charsets.UTF_8)
    }

    private class CapturingBridgeSocket(responsePayload: String) : BridgeSocket {
        override val input = ByteArrayInputStream(frame(responsePayload))
        override val output = ByteArrayOutputStream()
        override var readTimeoutMillis: Int = 0
        val written: ByteArrayOutputStream get() = output as ByteArrayOutputStream
        override fun close() = Unit

        private fun frame(payload: String): ByteArray {
            val output = ByteArrayOutputStream()
            val bytes = payload.toByteArray(Charsets.UTF_8)
            output.write((bytes.size ushr 24) and 0xff)
            output.write((bytes.size ushr 16) and 0xff)
            output.write((bytes.size ushr 8) and 0xff)
            output.write(bytes.size and 0xff)
            output.write(bytes)
            return output.toByteArray()
        }
    }

    private class TimeoutBridgeSocket : BridgeSocket {
        override val input = object : ByteArrayInputStream(ByteArray(0)) {
            override fun read(): Int {
                throw SocketTimeoutException("test timeout")
            }
        }
        override val output = ByteArrayOutputStream()
        override var readTimeoutMillis: Int = 0
        override fun close() = Unit
    }

    private class BlockingBridgeSocket : BridgeSocket {
        private val lock = Object()
        override val input = object : InputStream() {
            override fun read(): Int {
                readStarted.complete(Unit)
                synchronized(lock) {
                    while (!isClosed) {
                        lock.wait()
                    }
                }
                return -1
            }
        }
        override val output = ByteArrayOutputStream()
        override var readTimeoutMillis: Int = 0
        val readStarted = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        @Volatile
        private var isClosed = false

        override fun close() {
            synchronized(lock) {
                isClosed = true
                lock.notifyAll()
            }
            closed.complete(Unit)
        }
    }

    private class FakeAdbFacade(
        private val sessionJson: String,
    ) : AdbFacade {
        val forwarded = mutableListOf<Pair<Int, String>>()
        val removedForwards = mutableListOf<Int>()
        val pulled = mutableListOf<Pair<String, File>>()

        override fun devices(): List<AdbDevice> = listOf(AdbDevice("emulator-5554", "device"))

        override fun runAsCat(packageName: String, path: String): String {
            assertEquals("io.github.pointpatch.sample", packageName)
            assertEquals("files/pointpatch/session.json", path)
            return sessionJson
        }

        override fun forward(localPort: Int, socketAddress: String) {
            forwarded += localPort to socketAddress
        }

        override fun removeForward(localPort: Int) {
            removedForwards += localPort
        }

        override fun pull(androidPath: String, destination: File) {
            pulled += androidPath to destination
        }
    }
}
