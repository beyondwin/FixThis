package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.TreeKind
import io.beyondwin.fixthis.mcp.console.events.ConsoleEvent
import io.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.beyondwin.fixthis.mcp.fixtures.ConsoleRouteTestFixtures.newConsoleSessionFixture
import io.beyondwin.fixthis.mcp.fixtures.ConsoleRouteTestFixtures.newConsoleSessionFixtureWithTempRoot
import io.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.beyondwin.fixthis.mcp.fixtures.FakeLongs
import io.beyondwin.fixthis.mcp.fixtures.NullableSequencedFingerprintBridge
import io.beyondwin.fixthis.mcp.fixtures.SecondCaptureIllegalArgumentBridge
import io.beyondwin.fixthis.mcp.fixtures.SequencedFingerprintBridge
import io.beyondwin.fixthis.mcp.fixtures.addCapturedScreenForTest
import io.beyondwin.fixthis.mcp.fixtures.captureFakeScreenForTest
import io.beyondwin.fixthis.mcp.session.AnnotationDto
import io.beyondwin.fixthis.mcp.session.AnnotationStatusDto
import io.beyondwin.fixthis.mcp.session.AnnotationTargetDto
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackSessionException
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import io.beyondwin.fixthis.mcp.session.SnapshotRootDto
import io.beyondwin.fixthis.mcp.session.SnapshotScreenshotDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import java.net.HttpURLConnection
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun ConsoleHttpTestClient.getJsonObject(path: String): JsonObject = fixThisJson
    .parseToJsonElement(get(path))
    .jsonObject

private fun HttpURLConnection.inputJsonObject(): JsonObject = fixThisJson
    .parseToJsonElement(inputStream.bufferedReader().readText())
    .jsonObject

private inline fun withTempProject(prefix: String, block: (java.io.File) -> Unit) {
    val projectRoot = Files.createTempDirectory(prefix).toFile()
    try {
        block(projectRoot)
    } finally {
        projectRoot.deleteRecursively()
    }
}

private inline fun withConsoleServer(service: FeedbackSessionService, block: (FeedbackConsoleServer) -> Unit) {
    val server = FeedbackConsoleServer(service = service, port = 0)
    server.start()
    try {
        block(server)
    } finally {
        server.stop()
    }
}

@Suppress("LargeClass")
class ConsoleFeedbackItemRoutesTest {
    @Test
    fun itemPatchUpdatesDraftAnnotation() {
        val fixture = newConsoleSessionFixture(
            clock = FakeLongs(100L, 200L, 300L, 400L).next,
            idGenerator = FakeIds("session-1", "item-1").next,
        )
        fixture.use { context ->
            val service = context.service
            val store = context.store
            val server = context.server
            val session = service.openSession(null, newSession = true)
            store.addScreen(
                session.sessionId,
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 100L,
                    displayName = "Screen 1",
                ),
            )
            store.addItem(
                session.sessionId,
                AnnotationDto(
                    itemId = "pending",
                    screenId = "screen-1",
                    createdAtEpochMillis = 0L,
                    updatedAtEpochMillis = 0L,
                    target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                    comment = "Before",
                ),
            )
            val connection = ConsoleHttpTestClient(server.url).connection(
                "/api/items/item-1",
                method = "PUT",
                body = """{"comment":"After","status":"in_progress"}""",
            )

            assertEquals(200, connection.responseCode)
            val payload = fixThisJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            val item = payload.getValue("items").jsonArray.single().jsonObject
            assertEquals("After", item.getValue("comment").jsonPrimitive.content)
            assertEquals("in_progress", item.getValue("status").jsonPrimitive.content)
            assertEquals("After", service.getSession("session-1").items.single().comment)
            assertEquals(AnnotationStatusDto.IN_PROGRESS, service.getSession("session-1").items.single().status)
        }
    }

    @Test
    fun itemPatchUsesRequestedSessionWhenCurrentSessionChanged() {
        val store = FeedbackSessionStore(
            clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L, 700L).next,
            idGenerator = FakeIds("session-1", "item-1", "session-2").next,
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session1 = service.openSession(null, newSession = true)
        store.addScreen(
            session1.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Screen 1",
            ),
        )
        store.addItem(
            session1.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = "screen-1",
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                comment = "Before",
            ),
        )
        service.openSession(null, newSession = true)
        withConsoleServer(service) { server ->
            val connection = ConsoleHttpTestClient(server.url).connection(
                "/api/items/item-1",
                method = "PUT",
                body = """{"sessionId":"session-1","comment":"After"}""",
            )

            assertEquals(200, connection.responseCode)
            assertEquals("After", service.getSession("session-1").items.single().comment)
        }
    }

    @Test
    fun batchSaveUsesRequestedSessionWhenCurrentSessionChanged() {
        val store = FeedbackSessionStore(
            clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L).next,
            idGenerator = FakeIds("session-a", "preview-a", "preview-screen-a", "session-b", "item-a").next,
        )
        val service = FeedbackSessionService(
            bridge = SequencedFingerprintBridge("fp-a", "fp-a"),
            store = store,
            projectRoot = Files.createTempDirectory("fixthis-session-scope").toString(),
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val sessionA = service.openSession(null, newSession = true)
        val preview = runBlocking { service.capturePreview(sessionA.sessionId) }
        service.openSession(null, newSession = true)
        withConsoleServer(service) { server ->
            val body = """
                {
                  "sessionId": "${sessionA.sessionId}",
                  "previewId": "${preview.previewId}",
                  "frozenFingerprint": "fp-a",
                  "items": [{
                    "targetType": "area",
                    "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                    "comment": "save into session A"
                  }]
                }
            """.trimIndent()
            val response = ConsoleHttpTestClient(server.url).postJson("/api/items/batch", body)

            assertEquals(200, response.statusCode)
            assertEquals(1, service.getSession(sessionA.sessionId).items.size)
            assertEquals("save into session A", service.getSession(sessionA.sessionId).items.single().comment)
            assertTrue(service.requireCurrentSession().sessionId != sessionA.sessionId)
            assertTrue(service.requireCurrentSession().items.isEmpty())
        }
    }

    @Test
    fun addItemEmitsExplicitRequestSessionWhenCurrentSessionDiffers() {
        val fixture = newConsoleSessionFixtureWithTempRoot(
            idGenerator = FakeIds("session-a", "screen-a", "session-b", "item-a").next,
        )
        val bus = ConsoleEventBus(clock = { 1L })
        val seen = LinkedBlockingQueue<ConsoleEvent>()
        bus.subscribe { event -> seen += event }
        val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
        try {
            val sessionA = fixture.service.openSession(null, newSession = true)
            val screenA = runBlocking { fixture.service.captureScreen(sessionA.sessionId) }
            fixture.service.openSession(null, newSession = true)
            server.start()

            val response = ConsoleHttpTestClient(server.url).postJson(
                "/api/items",
                """
                {
                  "sessionId": "${sessionA.sessionId}",
                  "screenId": "${screenA.screenId}",
                  "targetType": "area",
                  "bounds": { "left": 1, "top": 2, "right": 30, "bottom": 40 },
                  "comment": "Explicit session item"
                }
                """.trimIndent(),
            )
            assertEquals(200, response.statusCode, response.body)

            val event = generateSequence { seen.poll(1, TimeUnit.SECONDS) }
                .first { it.name == "session-updated" }
            assertEquals(sessionA.sessionId, event.data.getValue("sessionId").jsonPrimitive.content)
        } finally {
            server.stop()
            fixture.close()
        }
    }

    @Test
    @Suppress("LongMethod")
    fun savingDraftItemsAppendsOneScreenAndTwoItems() {
        withTempProject("fixthis-console-batch") { projectRoot ->
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds(
                        "session-1",
                        "preview-1",
                        "preview-screen-1",
                        "item-1",
                        "item-2",
                    ).next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            withConsoleServer(service) { server ->
                service.openSession(null, newSession = true)
                val preview = ConsoleHttpTestClient(server.url).getJsonObject("/api/preview")
                val previewId = preview.getValue("previewId").jsonPrimitive.content

                val connection = ConsoleHttpTestClient(server.url).connection(
                    "/api/items/batch",
                    method = "POST",
                    body = """
                    {
                      "previewId": "$previewId",
                      "items": [
                        {
                          "targetType": "area",
                          "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                          "comment": "Change headline"
                        },
                        {
                          "targetType": "area",
                          "bounds": {"left":120.0,"top":200.0,"right":260.0,"bottom":280.0},
                          "comment": "Add margin"
                        }
                      ]
                    }
                    """.trimIndent(),
                )

                assertEquals(200, connection.responseCode)
                val session = connection.inputJsonObject()
                assertEquals(1, session.getValue("screens").jsonArray.size)
                val items = session.getValue("items").jsonArray.map { it.jsonObject }
                assertEquals(2, items.size)
                assertEquals(
                    listOf("Change headline", "Add margin"),
                    items.map { it.getValue("comment").jsonPrimitive.content },
                )
                assertEquals(
                    listOf("preview-screen-1", "preview-screen-1"),
                    items.map { it.getValue("screenId").jsonPrimitive.content },
                )
            }
        }
    }

    @Test
    fun batchItemsApiReturnsConflictWhenLiveScreenFingerprintDiffersFromFrozenPreview() {
        withTempProject("fixthis-console-mismatch") { projectRoot ->
            val service = FeedbackSessionService(
                bridge = SequencedFingerprintBridge("frozen", "current"),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            withConsoleServer(service) { server ->
                service.openSession(null, newSession = true)
                val preview = ConsoleHttpTestClient(server.url).getJsonObject("/api/preview")
                val previewId = preview.getValue("previewId").jsonPrimitive.content
                val frozenScreen = preview.getValue("screen").jsonObject

                val connection = ConsoleHttpTestClient(server.url).connection(
                    "/api/items/batch",
                    method = "POST",
                    body = """
                    {
                      "previewId": "$previewId",
                      "frozenFingerprint": "frozen",
                      "screen": $frozenScreen,
                      "items": [
                        {
                          "targetType": "area",
                          "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                          "comment": "Change headline"
                        }
                      ]
                    }
                    """.trimIndent(),
                )

                assertEquals(409, connection.responseCode)
                val payload = fixThisJson
                    .parseToJsonElement(connection.errorStream.bufferedReader().readText())
                    .jsonObject
                assertEquals("screen_fingerprint_mismatch", payload.getValue("error").jsonPrimitive.content)
                assertEquals("frozen", payload.getValue("frozenFingerprint").jsonPrimitive.content)
                assertEquals("current", payload.getValue("currentFingerprint").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun batchItemsApiReturnsFingerprintUnavailableHeaderWhenCurrentFingerprintIsMissing() {
        withTempProject("fixthis-console-null-fingerprint") { projectRoot ->
            val service = FeedbackSessionService(
                bridge = NullableSequencedFingerprintBridge("frozen", null),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            withConsoleServer(service) { server ->
                service.openSession(null, newSession = true)
                val preview = ConsoleHttpTestClient(server.url).getJsonObject("/api/preview")
                val previewId = preview.getValue("previewId").jsonPrimitive.content
                val frozenScreen = preview.getValue("screen").jsonObject

                val connection = ConsoleHttpTestClient(server.url).connection(
                    "/api/items/batch",
                    method = "POST",
                    body = """
                    {
                      "previewId": "$previewId",
                      "frozenFingerprint": "frozen",
                      "screen": $frozenScreen,
                      "items": [
                        {
                          "targetType": "area",
                          "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                          "comment": "Change headline"
                        }
                      ]
                    }
                    """.trimIndent(),
                )

                assertEquals(200, connection.responseCode)
                assertEquals(
                    "current_fingerprint_unavailable",
                    connection.getHeaderField("X-FixThis-Fingerprint-Unavailable-Reason"),
                )
                val session = connection.inputJsonObject()
                assertFalse(session.containsKey("fingerprintUnavailableReason"))
                assertEquals(1, session.getValue("screens").jsonArray.size)
                assertEquals(1, session.getValue("items").jsonArray.size)
            }
        }
    }

    @Test
    fun batchItemsApiReturnsServerErrorWhenLiveRecaptureThrowsIllegalArgumentException() {
        withTempProject("fixthis-console-recapture-error") { projectRoot ->
            val service = FeedbackSessionService(
                bridge = SecondCaptureIllegalArgumentBridge(),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds(
                        "session-1",
                        "preview-1",
                        "preview-screen-1",
                        "recapture-screen-1",
                        "item-1",
                    ).next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            withConsoleServer(service) { server ->
                service.openSession(null, newSession = true)
                val preview = ConsoleHttpTestClient(server.url).getJsonObject("/api/preview")
                val previewId = preview.getValue("previewId").jsonPrimitive.content

                val connection = ConsoleHttpTestClient(server.url).connection(
                    "/api/items/batch",
                    method = "POST",
                    body = """
                    {
                      "previewId": "$previewId",
                      "items": [
                        {
                          "targetType": "area",
                          "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                          "comment": "Change headline"
                        }
                      ]
                    }
                    """.trimIndent(),
                )

                assertEquals(500, connection.responseCode)
                assertTrue(connection.errorStream.bufferedReader().readText().contains("recapture failed"))
            }
        }
    }

    @Test
    fun savingDraftItemsAllowsBlankCommentsForUnwrittenAnnotations() {
        withTempProject("fixthis-console-blank-batch") { projectRoot ->
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            withConsoleServer(service) { server ->
                service.openSession(null, newSession = true)
                val preview = ConsoleHttpTestClient(server.url).getJsonObject("/api/preview")
                val previewId = preview.getValue("previewId").jsonPrimitive.content

                val connection = ConsoleHttpTestClient(server.url).connection(
                    "/api/items/batch",
                    method = "POST",
                    body = """
                    {
                      "previewId": "$previewId",
                      "items": [
                        {
                          "targetType": "area",
                          "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                          "comment": ""
                        }
                      ]
                    }
                    """.trimIndent(),
                )

                assertEquals(200, connection.responseCode)
                val session = connection.inputJsonObject()
                val item = session.getValue("items").jsonArray.single().jsonObject
                assertEquals("", item.getValue("comment").jsonPrimitive.content)
                assertEquals("open", item.getValue("status").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun batchItemsApiDoesNotDuplicateSameWorkspaceDraftItemWhenPreviewFallsBackToScreen() {
        withTempProject("fixthis-console-idempotent-batch") { projectRoot ->
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = FeedbackSessionStore(
                    clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L).next,
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1", "item-2").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            service.openSession(null, newSession = true)
            val preview = runBlocking { service.capturePreview("session-1") }
            val screenJson = fixThisJson.encodeToString(preview.screen)

            withConsoleServer(service) { server ->
                val body = """
                    {
                      "workspaceId": "workspace-a",
                      "previewId": "${preview.previewId}",
                      "screen": $screenJson,
                      "items": [{
                        "draftItemId": "draft-a",
                        "targetType": "area",
                        "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                        "comment": "save once"
                      }]
                    }
                """.trimIndent()

                val first = ConsoleHttpTestClient(server.url).postJson("/api/items/batch", body)
                val second = ConsoleHttpTestClient(server.url).postJson("/api/items/batch", body)

                assertEquals(200, first.statusCode)
                assertEquals(200, second.statusCode)
                val stored = service.getSession("session-1")
                assertEquals(1, stored.screens.size)
                assertEquals(1, stored.items.size)
                assertEquals("workspace-a", stored.items.single().clientWorkspaceId)
                assertEquals("draft-a", stored.items.single().clientDraftItemId)
            }
        }
    }

    @Test
    fun batchItemsApiReturnsBadRequestForEmptyItemList() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        withConsoleServer(service) { server ->
            service.openSession(null, newSession = true)
            val connection = ConsoleHttpTestClient(server.url).connection(
                "/api/items/batch",
                method = "POST",
                body = """{"previewId":"preview-1","items":[]}""",
            )

            assertEquals(400, connection.responseCode)
            assertTrue(
                connection.errorStream.bufferedReader().readText().contains("At least one feedback item is required"),
            )
            assertEquals(0, bridge.captureCount)
        }
    }

    @Test
    fun batchItemsApiReturnsNotFoundForUnknownPreviewId() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        withConsoleServer(service) { server ->
            service.openSession(null, newSession = true)
            val connection = ConsoleHttpTestClient(server.url).connection(
                "/api/items/batch",
                method = "POST",
                body = """
                {
                  "previewId": "missing-preview",
                  "items": [
                    {
                      "targetType": "area",
                      "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                      "comment": "Change headline"
                    }
                  ]
                }
                """.trimIndent(),
            )

            assertEquals(404, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("PREVIEW_NOT_FOUND"))
            assertEquals(0, bridge.captureCount)
        }
    }

    @Test
    fun batchItemsApiReturnsBadRequestForInvalidPreviewTarget() {
        val bridge = FakeFixThisBridge()
        withTempProject("fixthis-console-invalid-target") { projectRoot ->
            val service = FeedbackSessionService(
                bridge = bridge,
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            withConsoleServer(service) { server ->
                service.openSession(null, newSession = true)
                val preview = ConsoleHttpTestClient(server.url).getJsonObject("/api/preview")
                val previewId = preview.getValue("previewId").jsonPrimitive.content

                val connection = ConsoleHttpTestClient(server.url).connection(
                    "/api/items/batch",
                    method = "POST",
                    body = """
                    {
                      "previewId": "$previewId",
                      "items": [
                        {
                          "targetType": "area",
                          "bounds": {"left":-1.0,"top":20.0,"right":110.0,"bottom":80.0},
                          "comment": "Change headline"
                        }
                      ]
                    }
                    """.trimIndent(),
                )

                assertEquals(400, connection.responseCode)
                assertTrue(connection.errorStream.bufferedReader().readText().contains("Selection bounds"))
                assertEquals(1, bridge.captureCount)
            }
        }
    }

    @Test
    fun previewSaveInProgressMapsToConflict() {
        val method = Class
            .forName("io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerKt")
            .getDeclaredMethod("toConsoleHttpException", FeedbackSessionException::class.java)
        method.isAccessible = true

        val httpError = method.invoke(
            null,
            FeedbackSessionException("PREVIEW_SAVE_IN_PROGRESS: Preview is already being saved: preview-1"),
        )

        val statusCode = httpError.javaClass.getDeclaredField("statusCode")
        statusCode.isAccessible = true
        assertEquals(409, statusCode.get(httpError))
    }

    @Test
    fun clearDraftApiKeepsSentItems() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(
                clock = { 100L },
                idGenerator = FakeIds(
                    "session-1",
                    "screen-1",
                    "item-1",
                    "batch-1",
                    "item-2",
                ).next,
            ),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.captureFakeScreenForTest(session.sessionId)
        service.addAreaFeedback(session.sessionId, screen.screenId, FixThisRect(0f, 0f, 10f, 10f), "Sent")
        service.sendDraftToAgent(session.sessionId)
        service.addAreaFeedback(session.sessionId, screen.screenId, FixThisRect(10f, 10f, 20f, 20f), "Draft")
        withConsoleServer(service) { server ->
            val clear = ConsoleHttpTestClient(server.url).connection("/api/items/draft")
            clear.requestMethod = "DELETE"

            assertEquals(200, clear.responseCode)
            val body = clear.inputStream.bufferedReader().readText()
            val comments = fixThisJson.parseToJsonElement(body).jsonObject
                .getValue("items")
                .jsonArray
                .map { it.jsonObject.getValue("comment").jsonPrimitive.content }
            assertEquals(listOf("Sent"), comments)
        }
    }

    @Test
    fun itemsApiUsesCapturedNodeBoundsInsteadOfRequestBounds() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "item-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
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
        withConsoleServer(service) { server ->
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
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        service.openSession(null, newSession = true)
        withConsoleServer(service) { server ->
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
            assertTrue(connection.errorStream.bufferedReader().readText().contains("SCREEN_NOT_FOUND"))
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForUnsupportedFields() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
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
        withConsoleServer(service) { server ->
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
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Unsupported feedback item field"))
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForInvalidAreaBounds() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
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
        withConsoleServer(service) { server ->
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
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Selection bounds"))
        }
    }

    @Test
    fun deleteScreenApiDeletesScreenAndLinkedItems() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null)
        service.addCapturedScreenForTest(session.sessionId, SnapshotDto("screen-1", 0L, displayName = "Main"))
        service.addAreaFeedback(session.sessionId, "screen-1", FixThisRect(0f, 0f, 10f, 10f), "Remove me")
        withConsoleServer(service) { server ->
            val connection = ConsoleHttpTestClient(server.url).connection("/api/screens/screen-1")
            connection.requestMethod = "DELETE"

            assertEquals(200, connection.responseCode)
            val payload = fixThisJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            assertTrue(payload.getValue("screens").jsonArray.isEmpty())
            assertTrue(payload.getValue("items").jsonArray.isEmpty())
        }
    }
}
