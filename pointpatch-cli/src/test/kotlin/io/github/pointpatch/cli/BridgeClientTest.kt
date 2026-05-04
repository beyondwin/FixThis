package io.github.pointpatch.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
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
    }

    @Test
    fun pullsAndroidScreenshotArtifactsAndRewritesDesktopPaths() {
        val root = temporaryFolder.newFolder()
        val adb = FakeAdbFacade(sessionJson = sessionJson(protocol = "1.0"))
        val client = BridgeClient(adb = adb, projectRoot = root)
        val annotation = Json.parseToJsonElement(
            """
                {
                  "id": "ann-123",
                  "screenshot": {
                    "fullPath": "/data/user/0/io.github.pointpatch.sample/cache/pointpatch/ann-123-full.png",
                    "cropPath": "/data/user/0/io.github.pointpatch.sample/cache/pointpatch/ann-123-crop.png"
                  }
                }
            """.trimIndent(),
        ).jsonObject

        val rewritten = client.pullArtifacts("io.github.pointpatch.sample", annotation)
        val screenshot = rewritten.getValue("screenshot").jsonObject

        assertEquals(
            root.resolve(".pointpatch/artifacts/ann-123/ann-123-full.png").absolutePath,
            screenshot.getValue("desktopFullPath").jsonPrimitive.content,
        )
        assertEquals(
            root.resolve(".pointpatch/artifacts/ann-123/ann-123-crop.png").absolutePath,
            screenshot.getValue("desktopCropPath").jsonPrimitive.content,
        )
        assertEquals(
            listOf(
                "/data/user/0/io.github.pointpatch.sample/cache/pointpatch/ann-123-full.png" to
                    root.resolve(".pointpatch/artifacts/ann-123/ann-123-full.png"),
                "/data/user/0/io.github.pointpatch.sample/cache/pointpatch/ann-123-crop.png" to
                    root.resolve(".pointpatch/artifacts/ann-123/ann-123-crop.png"),
            ),
            adb.pulled,
        )
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

    private class FakeAdbFacade(
        private val sessionJson: String,
    ) : AdbFacade {
        val forwarded = mutableListOf<Pair<Int, String>>()
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

        override fun pull(androidPath: String, destination: File) {
            pulled += androidPath to destination
        }
    }
}
