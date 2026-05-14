package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationAction
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleNavigationRoutesTest {
    @Test
    fun navigationApiPerformsAction() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(
            bridge,
            FeedbackSessionStore(),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val connection = ConsoleHttpTestClient(server.url).connection("/api/navigation")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"action":"back","captureAfter":false}""".toByteArray()) }

            assertEquals(200, connection.responseCode)
            val response = connection.inputStream.bufferedReader().readText()
            assertEquals(true, fixThisJson.parseToJsonElement(response).jsonObject["performed"]?.jsonPrimitive?.boolean)
            assertEquals(FeedbackNavigationAction.BACK, bridge.navigationRequests.single().action)
            assertFalse(bridge.navigationRequests.single().captureAfter)
        } finally {
            server.stop()
        }
    }

    @Test
    fun navigationApiRejectsUnknownAutomationFields() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(
            bridge,
            FeedbackSessionStore(),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val payloads = listOf(
                """{"action":"back","sequence":[]}""",
                """{"action":"back","script":"adb shell input keyevent BACK"}""",
            )

            payloads.forEach { payload ->
                val connection = ConsoleHttpTestClient(server.url).connection("/api/navigation")
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toByteArray()) }

                assertEquals(400, connection.responseCode)
                assertTrue(connection.errorStream.bufferedReader().readText().contains("Unsupported navigation field"))
            }
            assertTrue(bridge.navigationRequests.isEmpty())
        } finally {
            server.stop()
        }
    }
}
