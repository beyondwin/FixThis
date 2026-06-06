package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEvent
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleRouteTestFixtures.newConsoleSessionFixtureWithTempRoot
import io.github.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
                "src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventRoutes.kt",
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
    fun sessionUpdatedEventsCarrySummaryPayloadForPushFirstSidebar() {
        val fixture = newConsoleSessionFixtureWithTempRoot(
            idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
        )
        val bus = ConsoleEventBus(clock = { 1L })
        val seen = LinkedBlockingQueue<ConsoleEvent>()
        bus.subscribe { event -> seen += event }
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

            val sessionsEvent = generateSequence { seen.poll(1, TimeUnit.SECONDS) }
                .first { it.name == "sessions-updated" }
            val summary = sessionsEvent.data.getValue("summary").jsonObject
            assertEquals("session-1", summary.getValue("sessionId").jsonPrimitive.content)
            assertEquals("active", summary.getValue("status").jsonPrimitive.content)
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

    @Test
    fun eventDiagnosticsEndpointReturnsBusStats() {
        val fixture = newConsoleSessionFixtureWithTempRoot(
            idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
        )
        val bus = ConsoleEventBus(clock = { 1L })
        val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
        try {
            server.start()
            bus.emit("sessions-updated", buildJsonObject { put("sessionId", "session-1") })

            val connection = URI("${server.url}/api/events/diagnostics").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000

            assertEquals(200, connection.responseCode)
            val payload = Json.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            assertEquals("1", payload.getValue("emittedEvents").jsonPrimitive.content)
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
