package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.github.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.github.beyondwin.fixthis.mcp.fixtures.addCapturedScreenForTest
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotRootDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private inline fun withValidationConsoleServer(
    service: FeedbackSessionService,
    block: (FeedbackConsoleServer) -> Unit,
) {
    val server = FeedbackConsoleServer(service = service, port = 0)
    server.start()
    try {
        block(server)
    } finally {
        server.stop()
    }
}

private fun HttpURLConnection.errorText(): String = errorStream.bufferedReader().readText()

class ConsoleFeedbackItemValidationRoutesTest {
    @Test
    fun itemsApiUsesCapturedNodeBoundsInsteadOfRequestBounds() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "item-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val node = FixThisNode(
            uid = "compose:0:merged:10",
            composeNodeId = 10,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(10f, 20f, 110f, 70f),
            text = listOf("Pay now"),
        )
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 720f, 1600f), mergedNodes = listOf(node))),
                screenshot = SnapshotScreenshotDto(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )
        withValidationConsoleServer(service) { server ->
            val connection = ConsoleHttpTestClient(server.url).connection(
                "/api/items",
                method = "POST",
                body = """
                {
                  "screenId": "${screen.screenId}",
                  "targetType": "node",
                  "nodeUid": "${node.uid}",
                  "bounds": {"left":200.0,"top":300.0,"right":260.0,"bottom":340.0},
                  "comment": "Button copy is unclear"
                }
                """.trimIndent(),
            )

            assertEquals(200, connection.responseCode)
            val item = fixThisJson.decodeFromString(
                AnnotationDto.serializer(),
                connection.inputStream.bufferedReader().readText(),
            )
            assertEquals(AnnotationTargetDto.Node(node.uid, node.boundsInWindow), item.target)
            assertEquals(node, item.selectedNode)
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForUnknownScreenId() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        service.openSession(null, newSession = true)
        withValidationConsoleServer(service) { server ->
            val connection = ConsoleHttpTestClient(server.url).connection(
                "/api/items",
                method = "POST",
                body = """
                {
                  "screenId": "missing-screen",
                  "targetType": "area",
                  "bounds": {"left":0.0,"top":0.0,"right":10.0,"bottom":10.0},
                  "comment": "Bad screen"
                }
                """.trimIndent(),
            )

            assertEquals(400, connection.responseCode)
            assertTrue(connection.errorText().contains("SCREEN_NOT_FOUND"))
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForUnsupportedFields() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                screenshot = SnapshotScreenshotDto(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )
        withValidationConsoleServer(service) { server ->
            val connection = ConsoleHttpTestClient(server.url).connection(
                "/api/items",
                method = "POST",
                body = """
                {
                  "screenId": "${screen.screenId}",
                  "targetType": "area",
                  "bounds": {"left":0.0,"top":0.0,"right":10.0,"bottom":10.0},
                  "comment": "Bad field",
                  "screenID": "typo"
                }
                """.trimIndent(),
            )

            assertEquals(400, connection.responseCode)
            assertTrue(connection.errorText().contains("Unsupported feedback item field"))
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForInvalidAreaBounds() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                screenshot = SnapshotScreenshotDto(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )
        withValidationConsoleServer(service) { server ->
            val connection = ConsoleHttpTestClient(server.url).connection(
                "/api/items",
                method = "POST",
                body = """
                {
                  "screenId": "${screen.screenId}",
                  "targetType": "area",
                  "bounds": {"left":-1.0,"top":0.0,"right":10.0,"bottom":10.0},
                  "comment": "Bad bounds"
                }
                """.trimIndent(),
            )

            assertEquals(400, connection.responseCode)
            assertTrue(connection.errorText().contains("Selection bounds"))
        }
    }

    @Test
    fun deleteScreenApiDeletesScreenAndLinkedItems() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next),
            "/repo",
            "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null)
        service.addCapturedScreenForTest(session.sessionId, SnapshotDto("screen-1", 0L, displayName = "Main"))
        service.addAreaFeedback(session.sessionId, "screen-1", FixThisRect(0f, 0f, 10f, 10f), "Remove me")
        withValidationConsoleServer(service) { server ->
            val connection = ConsoleHttpTestClient(server.url).connection("/api/screens/screen-1")
            connection.requestMethod = "DELETE"

            assertEquals(200, connection.responseCode)
            val payload = fixThisJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            assertTrue(payload.getValue("screens").jsonArray.isEmpty())
            assertTrue(payload.getValue("items").jsonArray.isEmpty())
        }
    }
}
