package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.mcp.console.events.ConsoleEvent
import io.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.beyondwin.fixthis.mcp.fixtures.ConsoleRouteTestFixtures.newConsoleSessionFixtureWithTempRoot
import io.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConsoleEventsRoutesTest {
    @Test
    fun eventsEndpointStreamsInitialSnapshot() {
        val fixture = newConsoleSessionFixtureWithTempRoot(
            idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
        )
        val bus = ConsoleEventBus(clock = { 1L })
        val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
        try {
            server.start()
            val connection = URI("${server.url}/api/events").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            assertEquals(200, connection.responseCode)
            assertTrue(connection.contentType.startsWith("text/event-stream"))
            val reader = connection.inputStream.bufferedReader()
            val lines = generateSequence { reader.readLine() }
                .takeWhile { it.isNotBlank() }
                .toList()
            assertTrue(lines.contains("event: snapshot"))
            val dataLines = lines.filter { it.startsWith("data: ") }
            assertEquals(1, dataLines.size, "SSE event data must be one JSON payload, not pretty-printed lines")
            val payload = Json.parseToJsonElement(dataLines.single().removePrefix("data: ")).jsonObject
            assertTrue(payload.containsKey("devices"))
        } finally {
            server.stop()
            fixture.close()
        }
    }

    @Test
    fun eventsEndpointReturnsJsonErrorWhenSnapshotFailsBeforeStreamStarts() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(devicesError = IllegalStateException("adb unavailable")),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service)
        try {
            server.start()
            val connection = URI("${server.url}/api/events").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000

            assertEquals(500, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("adb unavailable"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun eventsRouteTreatsKeepAliveClientDisconnectAsNormalClosure() {
        val source = java.nio.file.Files.readString(
            java.nio.file.Paths.get(
                "src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleEventRoutes.kt",
            ),
        )

        assertTrue(source.contains("runCatching {"))
        assertTrue(source.contains("error.isClientDisconnect()"))
        assertTrue(source.contains("subscriberClosed.countDown()"))
    }

    @Test
    fun sessionOpenEmitsSessionEvents() {
        val fixture = newConsoleSessionFixtureWithTempRoot(
            idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
        )
        val bus = ConsoleEventBus(clock = { 1L })
        val seen = LinkedBlockingQueue<String>()
        bus.subscribe { event -> seen += event.name }
        val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
        try {
            server.start()
            val connection = URI("${server.url}/api/session/open").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty(CONSOLE_TOKEN_HEADER, server.consoleTokenForTests())
            connection.setRequestProperty("content-type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write("""{"newSession":true}""".toByteArray()) }
            assertEquals(200, connection.responseCode)

            assertEquals("session-updated", seen.poll(1, TimeUnit.SECONDS))
            assertEquals("sessions-updated", seen.poll(1, TimeUnit.SECONDS))
        } finally {
            server.stop()
            fixture.close()
        }
    }

    @Test
    fun previewDeviceAndConnectionRoutesEmitEvents() {
        val fixture = newConsoleSessionFixtureWithTempRoot(
            idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
        )
        val bus = ConsoleEventBus(clock = { 1L })
        val names = mutableListOf<String>()
        bus.subscribe { event -> names += event.name }
        val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
        try {
            fixture.service.openSession(null, newSession = true)
            server.start()
            get(server.url, "/api/connection")
            get(server.url, "/api/devices")
            get(server.url, "/api/preview")

            assertTrue(names.contains("connection-updated"), names.toString())
            assertTrue(names.contains("devices-updated"), names.toString())
            assertTrue(names.contains("preview-ready"), names.toString())
        } finally {
            server.stop()
            fixture.close()
        }
    }

    @Test
    fun previewReadyEventIncludesSessionId() {
        val fixture = newConsoleSessionFixtureWithTempRoot(
            idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
        )
        val bus = ConsoleEventBus(clock = { 1L })
        val seen = LinkedBlockingQueue<ConsoleEvent>()
        bus.subscribe { event -> seen += event }
        val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
        try {
            fixture.service.openSession(null, newSession = true)
            server.start()

            get(server.url, "/api/preview")

            val previewEvent = generateSequence { seen.poll(1, TimeUnit.SECONDS) }
                .first { it.name == "preview-ready" }
            assertEquals("session-1", previewEvent.data.getValue("sessionId").jsonPrimitive.content)
        } finally {
            server.stop()
            fixture.close()
        }
    }

    private fun get(baseUrl: String, path: String) {
        val connection = URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 1000
        connection.readTimeout = 1000
        assertEquals(200, connection.responseCode, connection.errorStream?.bufferedReader()?.readText())
        assertNotNull(connection.inputStream.bufferedReader().readText())
    }
}
