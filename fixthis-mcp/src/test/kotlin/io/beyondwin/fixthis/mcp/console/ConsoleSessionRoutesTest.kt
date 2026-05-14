package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPaths
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPersistence
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleSessionRoutesTest {
    @Test
    fun sessionApiDoesNotCreateSessionWhenHistoryIsEmpty() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)

            assertEquals("null", client.get("/api/session"))

            val sessions = fixThisJson.parseToJsonElement(client.get("/api/sessions")).jsonObject
                .getValue("sessions")
                .jsonArray
            assertEquals(0, sessions.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionsApiListsWorkspaces() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val sessions = ConsoleHttpTestClient(server.url).get("/api/sessions")

            assertTrue(sessions.contains("session-1"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionsApiFiltersByPackageNameQuery() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1", "session-2").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val matching = service.openSession("io.beyondwin.fixthis.sample", newSession = true)
        val other = service.openSession("io.beyondwin.fixthis.other", newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val sessions = ConsoleHttpTestClient(server.url)
                .get("/api/sessions?packageName=io.beyondwin.fixthis.sample")

            assertTrue(sessions.contains(matching.sessionId))
            assertFalse(sessions.contains(other.sessionId))
        } finally {
            server.stop()
        }
    }

    @Test
    fun openSessionApiSwitchesCurrentSession() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1", "session-2").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val first = service.openSession(null, newSession = true)
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/session/open")
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
            FakeFixThisBridge(),
            FeedbackSessionStore(),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/session/open")
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
        val projectRoot = Files.createTempDirectory("fixthis-console-save-fail").toFile()
        try {
            projectRoot.resolve(".fixthis").writeText("blocked")
            val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(projectRoot), clock = { 100L })
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = { "session-1" },
                    persistence = persistence,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = ConsoleHttpTestClient(server.url).connection(
                    "/api/session/open",
                    method = "POST",
                    body = """{"newSession":true}""",
                )

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
            FakeFixThisBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/session/close")
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
            FakeFixThisBridge(),
            FeedbackSessionStore(),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/session/close")
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
}
