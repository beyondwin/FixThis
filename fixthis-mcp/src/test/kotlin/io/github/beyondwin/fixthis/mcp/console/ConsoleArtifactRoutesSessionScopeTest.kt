package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.github.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.github.beyondwin.fixthis.mcp.fixtures.FakeLongs
import io.github.beyondwin.fixthis.mcp.fixtures.SessionScreenshotBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import java.net.URLEncoder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleArtifactRoutesSessionScopeTest {
    @Test
    fun previewScreenshotUsesExplicitSessionIdWhenCurrentSessionChanged() {
        val root = Files.createTempDirectory("fixthis-artifact-scope").toFile()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val service = FeedbackSessionService(
            bridge = SessionScreenshotBridge(png),
            store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
                idGenerator = FakeIds("session-a", "preview-a", "preview-screen-a", "session-b").next,
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val sessionA = service.openSession(null, newSession = true)
        val preview = kotlinx.coroutines.runBlocking { service.capturePreview(sessionA.sessionId) }
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val path = "/api/preview/${encode(preview.previewId)}/screenshot/full" +
                "?sessionId=${encode(sessionA.sessionId)}"
            val response = ConsoleHttpTestClient(server.url).getResponse(path)

            assertEquals(200, response.statusCode)
            assertTrue(response.contentTypeStartsWith("image/png"))
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun previewScreenshotRejectsMismatchedExplicitSessionId() {
        val root = Files.createTempDirectory("fixthis-artifact-mismatch").toFile()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val service = FeedbackSessionService(
            bridge = SessionScreenshotBridge(png),
            store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
                idGenerator = FakeIds("session-a", "preview-a", "preview-screen-a", "session-b").next,
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val sessionA = service.openSession(null, newSession = true)
        val preview = kotlinx.coroutines.runBlocking { service.capturePreview(sessionA.sessionId) }
        val sessionB = service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val path = "/api/preview/${encode(preview.previewId)}/screenshot/full" +
                "?sessionId=${encode(sessionB.sessionId)}"
            val response = ConsoleHttpTestClient(server.url).getResponse(path)

            assertEquals(404, response.statusCode)
        } finally {
            server.stop()
            root.deleteRecursively()
        }
        assertTrue(sessionA.sessionId != sessionB.sessionId)
    }

    @Test
    fun screenScreenshotUsesExplicitSessionIdWhenCurrentSessionChanged() {
        val root = Files.createTempDirectory("fixthis-screen-artifact-scope").toFile()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val service = FeedbackSessionService(
            bridge = SessionScreenshotBridge(png),
            store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
                idGenerator = FakeIds("session-a", "screen-a", "session-b").next,
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val sessionA = service.openSession(null, newSession = true)
        val screen = kotlinx.coroutines.runBlocking { service.captureScreen(sessionA.sessionId) }
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val path = "/api/screens/${encode(screen.screenId)}/screenshot/full?sessionId=${encode(sessionA.sessionId)}"
            val response = ConsoleHttpTestClient(server.url).getResponse(path)

            assertEquals(200, response.statusCode)
            assertTrue(response.contentTypeStartsWith("image/png"))
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteScreenUsesExplicitSessionIdWhenCurrentSessionChanged() {
        val root = Files.createTempDirectory("fixthis-screen-delete-scope").toFile()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val service = FeedbackSessionService(
            bridge = SessionScreenshotBridge(png),
            store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
                idGenerator = FakeIds("session-a", "screen-a", "session-b").next,
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val sessionA = service.openSession(null, newSession = true)
        val screen = kotlinx.coroutines.runBlocking { service.captureScreen(sessionA.sessionId) }
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection(
                "/api/screens/${encode(screen.screenId)}?sessionId=${encode(sessionA.sessionId)}",
                method = "DELETE",
            )

            assertEquals(200, connection.responseCode)
            assertTrue(service.getSession(sessionA.sessionId).screens.isEmpty())
            assertTrue(service.requireCurrentSession().sessionId != sessionA.sessionId)
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
