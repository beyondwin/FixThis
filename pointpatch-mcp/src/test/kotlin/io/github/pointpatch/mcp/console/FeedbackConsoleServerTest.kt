package io.github.pointpatch.mcp.console

import io.github.pointpatch.cli.pointPatchJson
import io.github.pointpatch.mcp.session.FakePointPatchBridge
import io.github.pointpatch.mcp.session.FeedbackNavigationAction
import io.github.pointpatch.mcp.session.FeedbackSessionPaths
import io.github.pointpatch.mcp.session.FeedbackSessionPersistence
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun consoleHtmlIncludesSessionPickerControls() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("id=\"sessions\""))
        assertTrue(html.contains("id=\"newSessionButton\""))
        assertTrue(html.contains("id=\"closeSessionButton\""))
        assertTrue(html.contains("/api/sessions"))
        assertTrue(html.contains("/api/session/open"))
    }

    @Test
    fun consoleHtmlIncludesNavigationControls() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("id=\"backButton\""))
        assertTrue(html.contains("id=\"captureAfterNavigation\""))
        assertTrue(html.contains("/api/navigation"))
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
    fun sessionsApiListsWorkspaces() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1").next),
            "/repo",
            "io.github.pointpatch.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val sessions = URL("${server.url}/api/sessions").readText()

            assertTrue(sessions.contains("session-1"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionsApiFiltersByPackageNameQuery() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1", "session-2").next),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val matching = service.openSession("io.github.pointpatch.sample", newSession = true)
        val other = service.openSession("io.github.pointpatch.other", newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val sessions = URL("${server.url}/api/sessions?packageName=io.github.pointpatch.sample").readText()

            assertTrue(sessions.contains(matching.sessionId))
            assertFalse(sessions.contains(other.sessionId))
        } finally {
            server.stop()
        }
    }

    @Test
    fun openSessionApiSwitchesCurrentSession() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1", "session-2").next),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val first = service.openSession(null, newSession = true)
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session/open").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"sessionId":"${first.sessionId}"}""".toByteArray()) }

            assertEquals(200, connection.responseCode)
            assertTrue(connection.inputStream.bufferedReader().readText().contains(first.sessionId))
        } finally {
            server.stop()
        }
    }

    @Test
    fun openSessionApiReturnsNotFoundForUnknownSessionId() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session/open").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"sessionId":"missing-session"}""".toByteArray()) }

            assertEquals(404, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Unknown feedback session"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionApiReturnsServerErrorForSessionSaveFailure() {
        val projectRoot = Files.createTempDirectory("pointpatch-console-save-fail").toFile()
        try {
            projectRoot.resolve(".pointpatch").writeText("blocked")
            val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(projectRoot), clock = { 100L })
            val service = FeedbackSessionService(
                bridge = FakePointPatchBridge(),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }, persistence = persistence),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.pointpatch.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = URL("${server.url}/api/session").openConnection() as HttpURLConnection

                assertEquals(500, connection.responseCode)
                assertTrue(connection.errorStream.bufferedReader().readText().contains("SESSION_SAVE_FAILED:"))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun closeSessionApiClosesCurrentSession() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1").next),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session/close").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("{}".toByteArray()) }

            assertEquals(200, connection.responseCode)
            val response = connection.inputStream.bufferedReader().readText()
            assertTrue(response.contains(session.sessionId))
            assertTrue(response.contains("closed"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun closeSessionApiReturnsNotFoundForUnknownSessionId() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session/close").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"sessionId":"missing-session"}""".toByteArray()) }

            assertEquals(404, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Unknown feedback session"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun navigationApiPerformsAction() {
        val bridge = FakePointPatchBridge()
        val service = FeedbackSessionService(
            bridge,
            FeedbackSessionStore(),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/navigation").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"action":"back","captureAfter":false}""".toByteArray()) }

            assertEquals(200, connection.responseCode)
            val response = connection.inputStream.bufferedReader().readText()
            assertEquals(true, pointPatchJson.parseToJsonElement(response).jsonObject["performed"]?.jsonPrimitive?.boolean)
            assertEquals(FeedbackNavigationAction.BACK, bridge.navigationRequests.single().action)
            assertFalse(bridge.navigationRequests.single().captureAfter)
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
    fun servesSessionOwnedScreenshotArtifactFromCurrentSession() = runBlocking {
        val projectRoot = Files.createTempDirectory("pointpatch-console").toFile()
        try {
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            val service = FeedbackSessionService(
                bridge = SessionScreenshotBridge(pngBytes),
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

    @Test
    fun servesLegacyScreenshotArtifactFromCurrentSession() = runBlocking {
        val projectRoot = Files.createTempDirectory("pointpatch-console").toFile()
        try {
            val artifact = projectRoot.resolve(".pointpatch/artifacts/screen-1/full.png")
            artifact.parentFile.mkdirs()
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            artifact.writeBytes(pngBytes)
            val service = FeedbackSessionService(
                bridge = LegacyScreenshotBridge(artifact),
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

    private class SessionScreenshotBridge(private val pngBytes: ByteArray) : PointPatchBridge {
        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.github.pointpatch.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
            JsonObject(emptyMap())

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

    private class LegacyScreenshotBridge(private val artifact: File) : PointPatchBridge {
        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.github.pointpatch.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
            JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = buildJsonObject {
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
