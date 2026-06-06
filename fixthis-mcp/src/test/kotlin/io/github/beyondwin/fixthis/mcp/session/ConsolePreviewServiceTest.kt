package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.console.FeedbackConsoleServer
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.github.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PREVIEW_SAMPLE_PACKAGE = "io.github.beyondwin.fixthis.sample"

/**
 * Companion test for [io.github.beyondwin.fixthis.mcp.console.PreviewRoutes]
 * covering the readiness classification path. The `/api/preview` HTTP owner
 * is [io.github.beyondwin.fixthis.mcp.console.PreviewRoutes]; this file is
 * named ConsolePreviewServiceTest to match the v0.3 follow-up spec's task 2
 * file manifest and the gradle `--tests "*ConsolePreviewService*"` filter.
 */
class ConsolePreviewServiceTest {
    @Test
    fun previewServiceClassifiesSemanticsOnlyCaptureAsCaptureUnavailable() {
        val bridge = FakeFixThisBridge(
            snapshotMutator = { _, payload ->
                JsonObject(payload.toMap().filterKeys { it != "screenshot" })
            },
        )
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(
                clock = { 100L },
                idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
            ),
            projectRoot = "/repo",
            defaultPackageName = PREVIEW_SAMPLE_PACKAGE,
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val connection = ConsoleHttpTestClient(server.url).connection("/api/preview")

            assertEquals(200, connection.responseCode)
            val body = fixThisJson
                .parseToJsonElement(connection.inputStream.bufferedReader().readText())
                .jsonObject
            assertEquals(false, body.getValue("previewAvailable").jsonPrimitive.boolean)
            val readiness = body.getValue("readiness").jsonObject
            assertEquals("CAPTURE_UNAVAILABLE", readiness.getValue("state").jsonPrimitive.content)
            assertEquals("Retry capture", readiness.getValue("nextAction").jsonPrimitive.content)
        } finally {
            server.stop()
        }
    }

    @Test
    fun previewServiceOmitsCaptureUnavailableReadinessOnHappyPath() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(
                clock = { 100L },
                idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
            ),
            projectRoot = "/repo",
            defaultPackageName = PREVIEW_SAMPLE_PACKAGE,
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val body = fixThisJson
                .parseToJsonElement(ConsoleHttpTestClient(server.url).get("/api/preview"))
                .jsonObject

            assertEquals(true, body.getValue("previewAvailable").jsonPrimitive.boolean)
            val readinessElement = body["readiness"]
            assertTrue(readinessElement == null || readinessElement is JsonNull)
        } finally {
            server.stop()
        }
    }
}
