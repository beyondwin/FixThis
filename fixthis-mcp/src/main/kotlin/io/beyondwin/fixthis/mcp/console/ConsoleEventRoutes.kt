package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.mcp.console.events.ConsoleEvent
import io.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.beyondwin.fixthis.mcp.session.FeedbackSessionList
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val HTTP_OK = 200
private const val INITIAL_EVENT_ID = 0L
private const val KEEP_ALIVE_TIMEOUT_SECONDS = 15L
private val sseJson = Json(fixThisJson) { prettyPrint = false }

internal class ConsoleEventRoutes(
    private val service: FeedbackSessionService,
    private val eventBus: ConsoleEventBus,
) : ConsoleRoute {
    override fun matches(path: String): Boolean = path == "/api/events"

    override fun handle(exchange: HttpExchange) {
        exchange.requireMethod("GET") {
            exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.responseHeaders.set("Connection", "keep-alive")
            exchange.sendResponseHeaders(HTTP_OK, 0)

            val output = exchange.responseBody
            val subscriberClosed = CountDownLatch(1)
            fun send(event: ConsoleEvent) {
                output.writeSseEvent(event.name, event.id, event.data)
            }

            try {
                output.writeSseEvent("snapshot", null, snapshot())
                val lastEventId = exchange.requestHeaders.getFirst("Last-Event-ID")?.toLongOrNull()
                    ?: exchange.queryParameter("lastEventId")?.toLongOrNull()
                    ?: INITIAL_EVENT_ID
                val replay = eventBus.eventsAfter(lastEventId)
                if (replay.overflow) {
                    output.writeSseEvent(
                        "replay-overflow",
                        null,
                        buildJsonObject {
                            replay.oldestAvailableEventId?.let { put("oldestAvailableEventId", it) }
                        },
                    )
                } else {
                    replay.events.forEach(::send)
                }
                val subscription = eventBus.subscribe { event ->
                    runCatching { send(event) }.onFailure { subscriberClosed.countDown() }
                }
                try {
                    while (!subscriberClosed.await(KEEP_ALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        output.write(": keep-alive\n\n".toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                } finally {
                    subscription.close()
                }
            } finally {
                output.close()
            }
        }
    }

    private fun snapshot(): JsonObject {
        val currentSession = service.currentSessionOrNull()
        val sessions = service.listSessions()
        val selectedSerial = service.selectedDeviceSerial()
        val devices = ConsoleDeviceList(
            devices = service.devices().map { it.toConsoleDevice(selectedSerial) },
            selectedSerial = selectedSerial,
        )
        val connection = runBlocking { service.connectionStatus() }
        return buildJsonObject {
            put("session", currentSession?.let { fixThisJson.encodeToJsonElement(it).jsonObject } ?: JsonNull)
            put("sessions", fixThisJson.encodeToJsonElement(FeedbackSessionList.serializer(), sessions).jsonObject)
            put("devices", fixThisJson.encodeToJsonElement(ConsoleDeviceList.serializer(), devices).jsonObject)
            put(
                "connection",
                fixThisJson.encodeToJsonElement(ConsoleConnectionStatus.serializer(), connection).jsonObject,
            )
        }
    }
}

private fun OutputStream.writeSseEvent(name: String, id: Long?, data: JsonObject) {
    if (id != null) write("id: $id\n".toByteArray(Charsets.UTF_8))
    write("event: $name\n".toByteArray(Charsets.UTF_8))
    val json = sseJson.encodeToString(JsonObject.serializer(), data)
    write("data: $json\n\n".toByteArray(Charsets.UTF_8))
    flush()
}
