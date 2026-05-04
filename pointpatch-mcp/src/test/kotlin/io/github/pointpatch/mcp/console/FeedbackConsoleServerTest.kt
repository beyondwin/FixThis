package io.github.pointpatch.mcp.console

import io.github.pointpatch.mcp.session.FakePointPatchBridge
import io.github.pointpatch.mcp.session.FeedbackSessionService
import io.github.pointpatch.mcp.session.FeedbackSessionStore
import io.github.pointpatch.mcp.tools.PointPatchBridge
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackConsoleServerTest {
    @Test
    fun servesIndexAndSessionJson() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val index = URL(server.url).readText()
            assertTrue(index.contains("PointPatch Feedback Console"))

            val session = URL("${server.url}/api/session").readText()
            assertTrue(session.contains("io.github.pointpatch.sample"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun rejectsUnsupportedMethods() {
        val service = FeedbackSessionService(FakePointPatchBridge(), FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            assertEquals(405, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun startUrlUsesConfiguredHostAndBoundPort() {
        val service = FeedbackSessionService(FakePointPatchBridge(), FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
        val server = FeedbackConsoleServer(service = service, host = "127.0.0.1", port = 0)
        val url = server.start()
        try {
            assertTrue(url.startsWith("http://127.0.0.1:"))
            assertEquals(url, server.url)
            assertTrue(URL(url).readText().contains("PointPatch Feedback Console"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun servesScreenshotArtifactFromCurrentSession() = runBlocking {
        val projectRoot = Files.createTempDirectory("pointpatch-console").toFile()
        try {
            val artifact = projectRoot.resolve(".pointpatch/artifacts/screen-1/full.png")
            artifact.parentFile.mkdirs()
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            artifact.writeBytes(pngBytes)
            val service = FeedbackSessionService(
                bridge = ScreenshotBridge(artifact),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.pointpatch.sample",
            )
            val session = service.openSession(null)
            service.captureScreen(session.sessionId)
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = URL("${server.url}/api/screens/screen-1/screenshot/full").openConnection() as HttpURLConnection

                assertEquals(200, connection.responseCode)
                assertEquals("image/png", connection.contentType)
                assertTrue(connection.inputStream.use { it.readBytes() }.contentEquals(pngBytes))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    private class FakeIds(vararg values: String) {
        private val queue = ArrayDeque(values.toList())
        val next: () -> String = { queue.removeFirst() }
    }

    private class ScreenshotBridge(private val artifact: File) : PointPatchBridge {
        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.github.pointpatch.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
            JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(packageName: String): JsonObject = buildJsonObject {
            put("activity", "MainActivity")
            put("sourceIndexAvailable", true)
            put("inspection", buildJsonObject {
                put("activity", "MainActivity")
                put("roots", JsonArray(emptyList()))
                put("errors", JsonArray(emptyList()))
            })
            put("screenshot", buildJsonObject {
                put("desktopFullPath", artifact.absolutePath)
            })
        }
    }
}
