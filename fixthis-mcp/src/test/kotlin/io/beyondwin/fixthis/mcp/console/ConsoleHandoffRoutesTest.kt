package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import io.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.beyondwin.fixthis.mcp.fixtures.ConsoleSourceFixtures
import io.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.beyondwin.fixthis.mcp.fixtures.FakeLongs
import io.beyondwin.fixthis.mcp.fixtures.captureFakeScreenForTest
import io.beyondwin.fixthis.mcp.fixtures.seedSessionWithOneItem
import io.beyondwin.fixthis.mcp.session.AnnotationDto
import io.beyondwin.fixthis.mcp.session.AnnotationTargetDto
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackDelivery
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleHandoffRoutesTest {
    @Test
    fun agentHandoffUsesRequestedSessionWhenCurrentSessionChanged() {
        val store = FeedbackSessionStore(
            clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L, 700L).next,
            idGenerator = FakeIds("session-a", "screen-a", "item-a", "session-b", "handoff-a").next,
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val sessionA = service.openSession(null, newSession = true)
        store.addScreen(sessionA.sessionId, SnapshotDto("screen-a", 100L, displayName = "A"))
        val item = store.addItem(
            sessionA.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = "screen-a",
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                comment = "handoff A",
            ),
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val body = """{"sessionId":"${sessionA.sessionId}","itemIds":["${item.itemId}"]}"""
            val response = ConsoleHttpTestClient(server.url).postJson("/api/agent-handoffs", body)

            assertEquals(200, response.statusCode)
            assertEquals(FeedbackDelivery.SENT, service.getSession(sessionA.sessionId).items.single().delivery)
            assertTrue(service.requireCurrentSession().items.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffApiSendsDraftAndClearsDraftList() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(
                clock = { 100L },
                idGenerator = FakeIds("session-1", "screen-1", "item-1", "batch-1").next,
            ),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.captureFakeScreenForTest(session.sessionId)
        service.addAreaFeedback(session.sessionId, screen.screenId, FixThisRect(0f, 0f, 10f, 10f), "Fix it")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":["item-1"]}""",
            )
            assertEquals(200, response.statusCode)
            val payload = fixThisJson.parseToJsonElement(response.body).jsonObject
            val sessionObj = payload["session"]!!.jsonObject
            assertTrue(sessionObj["handoffBatches"]?.jsonArray.orEmpty().isNotEmpty())
            assertEquals(
                "sent",
                sessionObj["items"]?.jsonArray?.single()?.jsonObject?.get("delivery")?.jsonPrimitive?.content,
            )
            val prompt = payload["prompt"]!!.jsonPrimitive.content
            assertTrue(prompt.contains("id: item-1"), "prompt should contain 'id: item-1', got:\n$prompt")
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffApiReturnsConflictWhenNoDraftItemsExist() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":["fake-id"]}""",
            )
            assertEquals(409, response.statusCode)
            assertTrue(response.body.contains("NO_DRAFT_FEEDBACK"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun saveToMcpToastMentionsAgentPickup() {
        val html = ConsoleSourceFixtures.readAll()
        assertTrue(html.contains("Saved to MCP ✓ — agent will pick up"))
        assertFalse(html.contains("Saved to MCP ✓\","), "Old toast text must be gone")
    }

    @Test
    fun handoffPreviewEndpointReturnsMarkdownForRequestedItems() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/handoff-preview",
                body = """{"itemIds":["$itemId"]}""",
            )
            assertEquals(200, response.statusCode)
            assertTrue(response.contentTypeStartsWith("text/markdown"), "got: ${response.header("Content-Type")}")
            assertTrue(response.body.contains("id: $itemId"), "expected 'id: $itemId' in:\n${response.body}")
            assertTrue(response.body.contains("session_id: $sessionId"), "expected 'session_id:' in:\n${response.body}")
            assertTrue(response.body.contains("agent_protocol:"), "expected agent_protocol block in:\n${response.body}")
        } finally {
            server.stop()
        }
    }

    @Test
    fun handoffPreviewEndpointRefreshesPersistedFileNotFoundSourceCandidate() {
        val fixture = staleSourceCandidateFixture()
        val server = FeedbackConsoleServer(service = fixture.service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/${fixture.sessionId}/handoff-preview",
                body = """{"itemIds":["${fixture.itemId}"]}""",
            )

            assertEquals(200, response.statusCode)
            assertTrue(response.body.contains("sample/src/main/java/Sample.kt:3"), response.body)
            assertFalse(response.body.contains("stale: file not found on host"), response.body)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffsRefreshesPersistedFileNotFoundSourceCandidate() {
        val fixture = staleSourceCandidateFixture()
        val server = FeedbackConsoleServer(service = fixture.service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"sessionId":"${fixture.sessionId}","itemIds":["${fixture.itemId}"]}""",
            )

            assertEquals(200, response.statusCode)
            val payload = fixThisJson.parseToJsonElement(response.body).jsonObject
            val prompt = payload.getValue("prompt").jsonPrimitive.content
            val candidate = payload.getValue("session").jsonObject
                .getValue("items").jsonArray
                .single()
                .jsonObject
                .getValue("sourceCandidates").jsonArray
                .single()
                .jsonObject
            assertTrue(prompt.contains("sample/src/main/java/Sample.kt:3"), prompt)
            assertFalse(prompt.contains("stale: file not found on host"), prompt)
            assertEquals("sample/src/main/java/Sample.kt", candidate.getValue("repoFile").jsonPrimitive.content)
            assertEquals(false, candidate.getValue("stale").jsonPrimitive.boolean)
        } finally {
            server.stop()
        }
    }

    @Test
    fun handoffPreviewEndpointRejectsEmptyItemIds() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, _) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/handoff-preview",
                body = """{"itemIds":[]}""",
            )
            assertEquals(400, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun handoffPreviewEndpointReturns404ForUnknownSession() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/00000000-0000-0000-0000-000000000000/handoff-preview",
                body = """{"itemIds":["x"]}""",
            )
            assertEquals(404, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun handoffPreviewEndpointEmitsJsonErrorBody() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, _) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/handoff-preview",
                body = """{"itemIds":[]}""",
            )
            assertEquals(400, response.statusCode)
            assertTrue(response.contentTypeStartsWith("application/json"), "got: ${response.header("Content-Type")}")
            assertTrue(response.body.contains("\"error\""), "expected error JSON body, got:\n${response.body}")
            assertTrue(
                response.body.contains("itemIds must not be empty"),
                "expected reason in body, got:\n${response.body}",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun markHandedOffEndpointUpdatesLastHandedOffAtForItems() {
        var nowMillis = 100L
        val store = FeedbackSessionStore(clock = { nowMillis })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        // Promote DRAFT to SENT so the item carries SENT delivery before the call.
        service.sendDraftToAgent(sessionId, listOf(itemId))
        nowMillis = 500L
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/items/mark-handed-off",
                body = """{"itemIds":["$itemId"]}""",
            )
            assertEquals(200, response.statusCode)
            assertTrue(
                response.contentTypeStartsWith("application/json"),
                "got: ${response.header("Content-Type")}",
            )
            val item = store.getSession(sessionId).items.first { it.itemId == itemId }
            assertEquals(500L, item.lastHandedOffAtEpochMillis)
            assertEquals(500L, item.updatedAtEpochMillis)
        } finally {
            server.stop()
        }
    }

    @Test
    fun markHandedOffEndpointRejectsEmptyItemIds() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, _) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/items/mark-handed-off",
                body = """{"itemIds":[]}""",
            )
            assertEquals(400, response.statusCode)
            assertTrue(
                response.contentTypeStartsWith("application/json"),
                "got: ${response.header("Content-Type")}",
            )
            assertTrue(response.body.contains("\"error\""), "expected error JSON body, got:\n${response.body}")
        } finally {
            server.stop()
        }
    }

    @Test
    fun markHandedOffEndpointReturns404ForUnknownSession() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/00000000-0000-0000-0000-000000000000/items/mark-handed-off",
                body = """{"itemIds":["x"]}""",
            )
            assertEquals(404, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun markHandedOffEndpointRequiresConsoleToken() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url, includeConsoleToken = false).postJson(
                path = "/api/sessions/$sessionId/items/mark-handed-off",
                body = """{"itemIds":["$itemId"]}""",
            )
            assertEquals(403, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffsAcceptsItemIdsAndReturnsRenderedPrompt() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (_, itemId) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":["$itemId"]}""",
            )
            assertEquals(200, response.statusCode)
            val payload = fixThisJson.parseToJsonElement(response.body).jsonObject
            assertTrue(payload.containsKey("session"), "response should have 'session', got: ${response.body}")
            assertTrue(payload.containsKey("prompt"), "response should have 'prompt', got: ${response.body}")
            val prompt = payload["prompt"]!!.jsonPrimitive.content
            assertTrue(prompt.contains("id: $itemId"), "prompt should contain 'id: $itemId', got:\n$prompt")
            val sessionObj = payload["session"]!!.jsonObject
            val itemDelivery = sessionObj["items"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["itemId"]!!.jsonPrimitive.content == itemId }["delivery"]!!.jsonPrimitive.content
            assertEquals("sent", itemDelivery)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffsRejectsLegacyPromptBody() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"prompt":"# old format"}""",
            )
            assertEquals(400, response.statusCode)
            assertTrue(
                response.body.contains("itemIds"),
                "error message should mention itemIds, got: ${response.body}",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffsRejectsEmptyItemIds() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":[]}""",
            )
            assertEquals(400, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffsFlipsOnlySpecifiedItemIdsToSentLeavesOthersAsDraft() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, keepItemId) = seedSessionWithOneItem(store, service)
        // Add a second DRAFT item that should NOT be flipped
        val secondItem = store.addItem(
            sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = "screen-1",
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(5f, 6f, 7f, 8f)),
                comment = "second draft",
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":["$keepItemId"]}""",
            )
            assertEquals(200, response.statusCode)
            val sessionAfter = store.getSession(sessionId)
            val keptItem = sessionAfter.items.first { it.itemId == keepItemId }
            val otherItem = sessionAfter.items.first { it.itemId == secondItem.itemId }
            assertEquals(FeedbackDelivery.SENT, keptItem.delivery, "specified item should flip to SENT")
            assertEquals(FeedbackDelivery.DRAFT, otherItem.delivery, "unspecified item should remain DRAFT")
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionResponseIncludesStaleAfterHandoffFalseInitially() {
        val store = FeedbackSessionStore(clock = { 100L })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/session")
            val items = fixThisJson.parseToJsonElement(body).jsonObject
                .getValue("items").jsonArray
            assertEquals(1, items.size)
            val item = items[0].jsonObject
            assertTrue(item.containsKey("staleAfterHandoff"), "missing staleAfterHandoff: $item")
            assertEquals(false, item.getValue("staleAfterHandoff").jsonPrimitive.boolean)
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionResponseStaleAfterHandoffTrueWhenUpdatedAfterSend() {
        var nowMillis = 100L
        val store = FeedbackSessionStore(clock = { nowMillis })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        nowMillis = 200L
        service.sendDraftToAgent(sessionId, listOf(itemId))
        nowMillis = 500L
        service.updateDraftFeedback(sessionId, itemId, label = null, severity = null, comment = "edited", status = null)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/session")
            val item = fixThisJson.parseToJsonElement(body).jsonObject
                .getValue("items").jsonArray
                .single { it.jsonObject.getValue("itemId").jsonPrimitive.content == itemId }
                .jsonObject
            assertEquals(true, item.getValue("staleAfterHandoff").jsonPrimitive.boolean)
            assertEquals(200L, item.getValue("lastHandedOffAtEpochMillis").jsonPrimitive.long)
            assertEquals(500L, item.getValue("updatedAtEpochMillis").jsonPrimitive.long)
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionResponseStaleAfterHandoffFalseAfterReSave() {
        var nowMillis = 100L
        val store = FeedbackSessionStore(clock = { nowMillis })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        nowMillis = 200L
        service.sendDraftToAgent(sessionId, listOf(itemId))
        nowMillis = 500L
        service.updateDraftFeedback(sessionId, itemId, label = null, severity = null, comment = "edited", status = null)
        nowMillis = 700L
        service.sendDraftToAgent(sessionId, listOf(itemId))
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/session")
            val item = fixThisJson.parseToJsonElement(body).jsonObject
                .getValue("items").jsonArray
                .single { it.jsonObject.getValue("itemId").jsonPrimitive.content == itemId }
                .jsonObject
            assertEquals(false, item.getValue("staleAfterHandoff").jsonPrimitive.boolean)
            assertEquals(700L, item.getValue("lastHandedOffAtEpochMillis").jsonPrimitive.long)
        } finally {
            server.stop()
        }
    }

    private data class StaleSourceCandidateFixture(
        val service: FeedbackSessionService,
        val sessionId: String,
        val itemId: String,
    )

    private fun staleSourceCandidateFixture(): StaleSourceCandidateFixture {
        val root = kotlin.io.path.createTempDirectory("fixthis-console-handoff-refresh-").toFile()
        val sourceIndex = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "src/main/java/Sample.kt",
                    line = 3,
                    text = listOf("Email address"),
                    testTags = listOf("emailField"),
                    excerpt = "Text(\"Email address\")",
                ),
            ),
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(sourceIndex = sourceIndex),
            store = FeedbackSessionStore(
                clock = { 100L },
                idGenerator = FakeIds("session-1", "screen-1", "item-1", "batch-1").next,
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.captureFakeScreenForTest(session.sessionId)
        val item = runBlocking {
            service.addFeedbackItem(
                sessionId = session.sessionId,
                screenId = screen.screenId,
                targetType = FeedbackTargetType.NODE,
                bounds = FixThisRect(28f, 77f, 692f, 186f),
                nodeUid = "email-label",
                comment = "Needs source check",
            )
        }
        File(root, "sample/src/main/java/Sample.kt").apply {
            parentFile.mkdirs()
            writeText("package x\n\nText(\"Email address\")\n")
        }
        return StaleSourceCandidateFixture(service, session.sessionId, item.itemId)
    }
}
