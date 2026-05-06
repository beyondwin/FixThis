package io.beyondwin.fixthis.cli

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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
        root.resolve(".fixthis").mkdirs()
        root.resolve(".fixthis/project.json").writeText(
            """{"schemaVersion":"1.0","applicationId":"io.beyondwin.fixthis.fromfile"}""",
        )

        assertEquals(
            "io.beyondwin.fixthis.override",
            ProjectConfig.resolvePackageName(root, "io.beyondwin.fixthis.override"),
        )
    }

    @Test
    fun resolvesPackageFromProjectConfig() {
        val root = temporaryFolder.newFolder()
        root.resolve(".fixthis").mkdirs()
        root.resolve(".fixthis/project.json").writeText(
            """{"schemaVersion":"1.0","applicationId":"io.beyondwin.fixthis.sample"}""",
        )

        assertEquals("io.beyondwin.fixthis.sample", ProjectConfig.resolvePackageName(root, null))
    }

    @Test
    fun parsesDeviceMetadataFromAdbDevicesLongOutput() {
        val adb = FakeAdbFacade(
            sessionJson = sessionJson(protocol = "1.0"),
            devices = listOf(
                AdbDevice(
                    serial = "adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp",
                    state = "device",
                    model = "SM_G986N",
                    product = "y2qksx",
                    deviceName = "y2q",
                ),
                AdbDevice(
                    serial = "emulator-5554",
                    state = "offline",
                    model = "sdk_gphone64",
                    product = "sdk_phone64",
                    deviceName = "emu64",
                ),
            ),
        )
        val client = BridgeClient(adb = adb, projectRoot = temporaryFolder.newFolder())

        val devices = client.devices()

        assertEquals(2, devices.size)
        assertEquals("SM_G986N", devices.first().model)
        assertEquals("offline", devices[1].state)
    }

    @Test
    fun selectedDeviceSerialScopesBridgeAdbCommands() = runBlocking {
        val adb = FakeAdbFacade(
            sessionJson = sessionJson(protocol = "1.0"),
            devices = listOf(
                AdbDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp", "device"),
            ),
        )
        val socket = CapturingBridgeSocket(
            responsePayload = """
                {
                  "id": "req_1",
                  "ok": true,
                  "result": {
                    "bridgeProtocolVersion": "1.0",
                    "activity": "MainActivity"
                  }
                }
            """.trimIndent(),
        )
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 },
            socketConnector = { socket },
        )

        client.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        client.request("io.beyondwin.fixthis.sample", "status")

        assertEquals("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp", client.selectedDeviceSerial())
        assertEquals(listOf("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp"), adb.runAsSerials)
        assertEquals(listOf("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp"), adb.forwardSerials)
        assertEquals(listOf("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp"), adb.removeForwardSerials)
    }

    @Test
    fun selectedDeviceSerialMustBeConnected() = runBlocking {
        val adb = FakeAdbFacade(
            sessionJson = sessionJson(protocol = "1.0"),
            devices = listOf(AdbDevice("device-1", "device")),
        )
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 },
            socketConnector = { error("socket should not be opened") },
        )

        client.selectDevice("missing-device")
        val error = kotlin.runCatching {
            client.request("io.beyondwin.fixthis.sample", "status")
        }.exceptionOrNull()

        assertTrue(error is NoDeviceException)
        assertEquals(emptyList<String?>(), adb.runAsSerials)
        assertEquals(emptyList<String?>(), adb.forwardSerials)
    }

    @Test
    fun selectedDeviceSerialMustBeReady() = runBlocking {
        val adb = FakeAdbFacade(
            sessionJson = sessionJson(protocol = "1.0"),
            devices = listOf(AdbDevice("device-1", "offline")),
        )
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 },
            socketConnector = { error("socket should not be opened") },
        )

        client.selectDevice("device-1")
        val error = kotlin.runCatching {
            client.request("io.beyondwin.fixthis.sample", "status")
        }.exceptionOrNull()

        assertTrue(error is NoDeviceException)
        assertEquals(emptyList<String?>(), adb.runAsSerials)
        assertEquals(emptyList<String?>(), adb.forwardSerials)
    }

    @Test
    fun disconnectDeviceClearsOnlyClientSelection() {
        val client = BridgeClient(
            adb = FakeAdbFacade(sessionJson = sessionJson(protocol = "1.0")),
            projectRoot = temporaryFolder.newFolder(),
        )

        client.selectDevice("device-1")
        client.disconnectDevice()

        assertEquals(null, client.selectedDeviceSerial())
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

        val result = client.request("io.beyondwin.fixthis.sample", "status")

        assertEquals("MainActivity", result.getValue("activity").jsonPrimitive.content)
        assertEquals(
            listOf(34567 to "localabstract:fixthis_io.beyondwin.fixthis.sample"),
            adb.forwarded,
        )
        assertEquals(listOf(34567), adb.removedForwards)
        val request = Json.parseToJsonElement(readFrame(socket.written.toByteArray())).jsonObject
        assertEquals("req_1", request.getValue("id").jsonPrimitive.content)
        assertEquals("token-1", request.getValue("token").jsonPrimitive.content)
        assertEquals("status", request.getValue("method").jsonPrimitive.content)
    }

    @Test
    fun readSourceIndexFramesDedicatedBridgeMethod() = runBlocking {
        val adb = FakeAdbFacade(sessionJson = sessionJson(protocol = "1.0"))
        val socket = CapturingBridgeSocket(
            responsePayload = """
                {
                  "id": "req_1",
                  "ok": true,
                  "result": {
                    "bridgeProtocolVersion": "1.0",
                    "sourceIndexAvailable": true,
                    "sourceIndex": {
                      "schemaVersion": "1.0",
                      "entries": [
                        { "file": "sample/src/main/java/FormScreen.kt", "line": 37 }
                      ]
                    }
                  }
                }
            """.trimIndent(),
        )
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 },
            socketConnector = { socket },
        )

        val result = client.readSourceIndex("io.beyondwin.fixthis.sample")

        assertTrue(result.getValue("sourceIndexAvailable").jsonPrimitive.boolean)
        assertTrue(result.getValue("sourceIndex").jsonObject.toString().contains("FormScreen.kt"))
        val request = Json.parseToJsonElement(readFrame(socket.written.toByteArray())).jsonObject
        assertEquals("readSourceIndex", request.getValue("method").jsonPrimitive.content)
    }

    @Test
    fun performNavigationFramesBridgeMethodAndParams() = runBlocking {
        val adb = FakeAdbFacade(sessionJson = sessionJson(protocol = "1.0"))
        val socket = CapturingBridgeSocket(
            responsePayload = """
                {
                  "id": "req_1",
                  "ok": true,
                  "result": {
                    "bridgeProtocolVersion": "1.0",
                    "performed": true,
                    "activity": "MainActivity"
                  }
                }
            """.trimIndent(),
        )
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 },
            socketConnector = { socket },
        )

        val result = client.performNavigation(
            "io.beyondwin.fixthis.sample",
            buildJsonObject {
                put("action", "back")
                put("captureAfter", false)
            },
        )
        val request = Json.parseToJsonElement(readFrame(socket.written.toByteArray())).jsonObject
        val params = request.getValue("params").jsonObject

        assertEquals(true, result.getValue("performed").jsonPrimitive.boolean)
        assertEquals("performNavigation", request.getValue("method").jsonPrimitive.content)
        assertEquals("back", params.getValue("action").jsonPrimitive.content)
        assertEquals(false, params.getValue("captureAfter").jsonPrimitive.boolean)
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
            client.request("io.beyondwin.fixthis.sample", "status")
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
            client.request("io.beyondwin.fixthis.sample", "status")
        }.exceptionOrNull()

        assertTrue(error is BridgeConnectionException)
        assertEquals(
            listOf(34567 to "localabstract:fixthis_io.beyondwin.fixthis.sample"),
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
            client.request("io.beyondwin.fixthis.sample", "status")
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
            client.request("io.beyondwin.fixthis.sample", "status")
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
                                "fullPath": "/data/user/0/io.beyondwin.fixthis.sample/cache/fixthis/ann-123-full.png",
                                "cropPath": "/data/user/0/io.beyondwin.fixthis.sample/cache/fixthis/ann-123-crop.png"
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
                            "path": "/data/user/0/io.beyondwin.fixthis.sample/cache/fixthis/ann-123-full.png",
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
                            "path": "/data/user/0/io.beyondwin.fixthis.sample/cache/fixthis/ann-123-crop.png",
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

        val result = client.startFeedbackCapture("io.beyondwin.fixthis.sample", timeoutMillis = 120_000L)
        val screenshot = result.getValue("annotation").jsonObject.getValue("screenshot").jsonObject

        val fullDestination = root.resolve(".fixthis/artifacts/ann-123/ann-123-full.png")
        val cropDestination = root.resolve(".fixthis/artifacts/ann-123/ann-123-crop.png")
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
    fun nestedScreenshotReadsPreserveParentSelectedDevice() = runBlocking {
        val root = temporaryFolder.newFolder()
        lateinit var client: BridgeClient
        val adb = FakeAdbFacade(
            sessionJson = sessionJson(protocol = "1.0"),
            devices = listOf(
                AdbDevice("device-1", "device"),
                AdbDevice("device-2", "device"),
            ),
        )
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
                                "fullPath": "/data/user/0/io.beyondwin.fixthis.sample/cache/fixthis/ann-123-full.png",
                                "cropPath": "/data/user/0/io.beyondwin.fixthis.sample/cache/fixthis/ann-123-crop.png"
                              }
                            }
                          }
                        }
                    """.trimIndent(),
                    onClose = { client.selectDevice("device-2") },
                ),
                CapturingBridgeSocket(
                    responsePayload = """
                        {
                          "id": "req_2",
                          "ok": true,
                          "result": {
                            "bridgeProtocolVersion": "1.0",
                            "path": "/data/user/0/io.beyondwin.fixthis.sample/cache/fixthis/ann-123-full.png",
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
                            "path": "/data/user/0/io.beyondwin.fixthis.sample/cache/fixthis/ann-123-crop.png",
                            "kind": "crop",
                            "mimeType": "image/png",
                            "base64": "Y3JvcC1wbmc="
                          }
                        }
                    """.trimIndent(),
                ),
            )
        val sockets = ArrayDeque(bridgeSockets)
        client = BridgeClient(
            adb = adb,
            projectRoot = root,
            portAllocator = { 34567 + adb.forwarded.size },
            socketConnector = { sockets.removeFirst() },
        )

        client.selectDevice("device-1")
        client.startFeedbackCapture("io.beyondwin.fixthis.sample", timeoutMillis = 120_000L)

        assertEquals(listOf("device-1", "device-1", "device-1"), adb.runAsSerials)
        assertEquals(listOf("device-1", "device-1", "device-1"), adb.forwardSerials)
        assertEquals(listOf("device-1", "device-1", "device-1"), adb.removeForwardSerials)
        assertEquals("device-2", client.selectedDeviceSerial())
    }

    @Test
    fun captureScreenSnapshotPullsFullScreenshotArtifact() = runBlocking {
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
                            "bridgeProtocolVersion": "1.0",
                            "activity": "MainActivity",
                            "inspection": {
                              "activity": "MainActivity",
                              "roots": [],
                              "errors": []
                            },
                            "screenshot": {
                              "fullPath": "/data/user/0/pkg/cache/fixthis/full.png"
                            },
                            "sourceIndexAvailable": true
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
                            "path": "/data/user/0/pkg/cache/fixthis/full.png",
                            "kind": "full",
                            "mimeType": "image/png",
                            "base64": "iVBORw0KGgo="
                          }
                        }
                    """.trimIndent(),
                ),
            )
        val sockets = ArrayDeque(bridgeSockets)
        val client = BridgeClient(
            adb = adb,
            projectRoot = root,
            portAllocator = { 41001 + adb.forwarded.size },
            socketConnector = { sockets.removeFirst() },
        )

        val result = client.captureScreenSnapshot("io.beyondwin.fixthis.sample")
        val screenshot = result.getValue("screenshot").jsonObject
        val readScreenshotRequest = Json.parseToJsonElement(readFrame(bridgeSockets[1].written.toByteArray())).jsonObject
        val readScreenshotParams = readScreenshotRequest.getValue("params").jsonObject

        assertTrue(screenshot.getValue("desktopFullPath").jsonPrimitive.content.endsWith("-full.png"))
        assertEquals(
            listOf("captureScreenSnapshot", "readScreenshot"),
            bridgeSockets.map { socket ->
                Json.parseToJsonElement(readFrame(socket.written.toByteArray()))
                    .jsonObject
                    .getValue("method")
                    .jsonPrimitive
                    .content
            },
        )
        assertEquals("full", readScreenshotParams.getValue("kind").jsonPrimitive.content)
        assertEquals("screenSnapshot", readScreenshotParams.getValue("source").jsonPrimitive.content)
    }

    @Test
    fun captureScreenSnapshotUsesProvidedDestinationDirectory() = runBlocking {
        val root = temporaryFolder.newFolder()
        val destinationDirectory = root.resolve(".fixthis/feedback-sessions/session-1/artifacts/screens/screen-1")
        val adb = FakeAdbFacade(sessionJson = sessionJson(protocol = "1.0"))
        val bridgeSockets =
            listOf(
                CapturingBridgeSocket(
                    responsePayload = """
                        {
                          "id": "req_1",
                          "ok": true,
                          "result": {
                            "bridgeProtocolVersion": "1.0",
                            "activity": "MainActivity",
                            "inspection": {
                              "activity": "MainActivity",
                              "roots": [],
                              "errors": []
                            },
                            "screenshot": {
                              "fullPath": "/data/user/0/pkg/cache/fixthis/full.png"
                            },
                            "sourceIndexAvailable": true
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
                            "path": "/data/user/0/pkg/cache/fixthis/full.png",
                            "kind": "full",
                            "mimeType": "image/png",
                            "base64": "iVBORw0KGgo="
                          }
                        }
                    """.trimIndent(),
                ),
            )
        val sockets = ArrayDeque(bridgeSockets)
        val client = BridgeClient(
            adb = adb,
            projectRoot = root,
            portAllocator = { 42001 + adb.forwarded.size },
            socketConnector = { sockets.removeFirst() },
        )

        val result = client.captureScreenSnapshot(
            packageName = "io.beyondwin.fixthis.sample",
            sessionId = "session-1",
            screenId = "screen-1",
            destinationDirectory = destinationDirectory,
        )
        val screenshot = result.getValue("screenshot").jsonObject
        val expectedFile = destinationDirectory.resolve("screen-1-full.png")

        assertEquals(expectedFile.absolutePath, screenshot.getValue("desktopFullPath").jsonPrimitive.content)
        assertTrue(expectedFile.exists())
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

        val result = client.startFeedbackCapture("io.beyondwin.fixthis.sample", timeoutMillis = 120_000L)
        val screenshot = result.getValue("annotation").jsonObject.getValue("screenshot").jsonObject

        assertTrue("desktopFullPath" !in screenshot)
        assertTrue("desktopCropPath" !in screenshot)
        assertEquals(emptyList<Pair<String, File>>(), adb.pulled)
    }

    private fun sessionJson(protocol: String): String =
        """
            {
              "schemaVersion": "1.0",
              "packageName": "io.beyondwin.fixthis.sample",
              "socketName": "fixthis_io.beyondwin.fixthis.sample",
              "socketAddress": "localabstract:fixthis_io.beyondwin.fixthis.sample",
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

    private class CapturingBridgeSocket(
        responsePayload: String,
        private val onClose: () -> Unit = {},
    ) : BridgeSocket {
        override val input = ByteArrayInputStream(frame(responsePayload))
        override val output = ByteArrayOutputStream()
        override var readTimeoutMillis: Int = 0
        val written: ByteArrayOutputStream get() = output as ByteArrayOutputStream
        override fun close() {
            onClose()
        }

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
        private val devices: List<AdbDevice> = listOf(AdbDevice("emulator-5554", "device")),
        private val selectedSerial: String? = null,
        val forwarded: MutableList<Pair<Int, String>> = mutableListOf(),
        val removedForwards: MutableList<Int> = mutableListOf(),
        val pulled: MutableList<Pair<String, File>> = mutableListOf(),
        val runAsSerials: MutableList<String?> = mutableListOf(),
        val forwardSerials: MutableList<String?> = mutableListOf(),
        val removeForwardSerials: MutableList<String?> = mutableListOf(),
    ) : AdbFacade {
        override fun devices(): List<AdbDevice> = devices

        override fun forDevice(serial: String?): AdbFacade =
            FakeAdbFacade(
                sessionJson = sessionJson,
                devices = devices,
                selectedSerial = serial,
                forwarded = forwarded,
                removedForwards = removedForwards,
                pulled = pulled,
                runAsSerials = runAsSerials,
                forwardSerials = forwardSerials,
                removeForwardSerials = removeForwardSerials,
            )

        override fun runAsCat(packageName: String, path: String): String {
            runAsSerials += selectedSerial
            assertEquals("io.beyondwin.fixthis.sample", packageName)
            assertEquals("files/fixthis/session.json", path)
            return sessionJson
        }

        override fun forward(localPort: Int, socketAddress: String) {
            forwardSerials += selectedSerial
            forwarded += localPort to socketAddress
        }

        override fun removeForward(localPort: Int) {
            removeForwardSerials += selectedSerial
            removedForwards += localPort
        }

        override fun pull(androidPath: String, destination: File) {
            pulled += androidPath to destination
        }
    }
}
