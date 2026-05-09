package io.beyondwin.fixthis.mcp

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.AnnotationStatusDto
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationRequest
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.tools.FixThisBridge
import io.beyondwin.fixthis.mcp.tools.FixThisTools
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {
    @Test
    fun mcpCompatibilityFixtureInitialize() {
        val responses = runCompatibilityTranscript(
            """{"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"fixture","version":"1"}}}""",
        )

        val response = responses.single().jsonObject
        val result = response.getValue("result").jsonObject
        assertEquals("init", response.getValue("id").jsonPrimitive.content)
        assertEquals("2025-06-18", result.getValue("protocolVersion").jsonPrimitive.content)
        assertTrue(result.getValue("capabilities").jsonObject.containsKey("tools"))
        assertTrue(result.getValue("capabilities").jsonObject.containsKey("resources"))
    }

    @Test
    fun mcpCompatibilityFixtureNotificationsProduceNoResponseAndDoNotCorruptNextResponse() {
        val responses = runCompatibilityTranscript(
            """{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}""",
            """{"jsonrpc":"2.0","id":"after-notification","method":"ping","params":{}}""",
        )

        assertEquals(listOf("after-notification"), responses.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertTrue(responses.single().jsonObject.containsKey("result"))
    }

    @Test
    fun mcpCompatibilityFixtureToolsList() {
        val response = runCompatibilityTranscript(
            """{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""",
        ).single().jsonObject

        val tools = response
            .getValue("result").jsonObject
            .getValue("tools").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }

        assertTrue("fixthis_status" in tools)
        assertTrue("fixthis_get_current_screen" in tools)
        assertTrue("fixthis_open_feedback_console" in tools)
        assertTrue("fixthis_resolve_feedback" in tools)
    }

    @Test
    fun mcpCompatibilityFixtureToolsCall() {
        val bridge = FakeBridge()
        val response = runCompatibilityTranscript(
            """{"jsonrpc":"2.0","id":"status","method":"tools/call","params":{"name":"fixthis_status","arguments":{"packageName":"io.beyondwin.fixthis.sample"}}}""",
            server = server(bridge),
        ).single().jsonObject

        val result = response.getValue("result").jsonObject
        val payload = parse(result.getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content).jsonObject
        assertEquals("status", response.getValue("id").jsonPrimitive.content)
        assertEquals(false, result.getValue("isError").jsonPrimitive.boolean)
        assertEquals("io.beyondwin.fixthis.sample.MainActivity", payload.getValue("currentActivity").jsonPrimitive.content)
        assertEquals("io.beyondwin.fixthis.sample", payload.getValue("packageName").jsonPrimitive.content)
        assertEquals(listOf("status:io.beyondwin.fixthis.sample"), bridge.calls)
    }

    @Test
    fun mcpCompatibilityFixtureResourcesList() {
        val response = runCompatibilityTranscript(
            """{"jsonrpc":"2.0","id":"resources","method":"resources/list","params":{}}""",
        ).single().jsonObject

        val resources = response
            .getValue("result").jsonObject
            .getValue("resources").jsonArray
            .map { it.jsonObject.getValue("uri").jsonPrimitive.content }

        assertEquals(
            listOf(
                "fixthis://session/current",
                "fixthis://screen/current",
                "fixthis://screenshot/latest/full.png",
                "fixthis://screenshot/latest/crop.png",
                "fixthis://source-index",
            ),
            resources,
        )
    }

    @Test
    fun mcpCompatibilityFixtureInvalidMessageRecovery() {
        val responses = runCompatibilityTranscript(
            """{"jsonrpc":"2.0","id":"bad","method":"ping"""",
            """{"jsonrpc":"2.0","id":"after-bad-line","method":"ping","params":{}}""",
        )

        assertEquals(2, responses.size)
        assertEquals(-32700, responses[0].jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        assertEquals("after-bad-line", responses[1].jsonObject.getValue("id").jsonPrimitive.content)
        assertTrue(responses[1].jsonObject.containsKey("result"))
    }

    @Test
    fun mcpCompatibilityFixtureCancellationNotificationKeepsServerResponsive() = runBlocking {
        val bridge = BlockingScreenToolBridge()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val input = PipedInputStream()
        val inputWriter = PipedOutputStream(input).bufferedWriter(Charsets.UTF_8)
        val job = launch(Dispatchers.IO) {
            server(bridge).run(input = input, output = stdout, diagnostics = stderr)
        }

        try {
            inputWriter.write("""{"jsonrpc":"2.0","id":"screen","method":"tools/call","params":{"name":"fixthis_get_current_screen","arguments":{}}}""")
            inputWriter.newLine()
            inputWriter.flush()
            withTimeout(1_000) { bridge.started.await() }

            inputWriter.write("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"screen","reason":"fixture cancellation"}}""")
            inputWriter.newLine()
            inputWriter.write("""{"jsonrpc":"2.0","id":"after-cancel","method":"ping","params":{}}""")
            inputWriter.newLine()
            inputWriter.flush()

            withTimeout(1_000) { bridge.cancelled.await() }
            withTimeout(1_000) {
                while (!stdout.toString().contains("after-cancel")) {
                    delay(10)
                }
            }

            val responses = stdout.toString().trim().lines().filter { it.isNotBlank() }.map(::parse)
            assertEquals(listOf("after-cancel"), responses.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        } finally {
            inputWriter.close()
            input.close()
            job.cancelAndJoin()
        }
    }

    @Test
    fun mcpCompatibilityFixtureEofCancelsPendingRequestAndReturnsPromptly() = runBlocking {
        val bridge = BlockingScreenToolBridge()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val input = PipedInputStream()
        val inputWriter = PipedOutputStream(input).bufferedWriter(Charsets.UTF_8)
        val job = launch(Dispatchers.IO) {
            server(bridge).run(input = input, output = stdout, diagnostics = stderr)
        }

        try {
            inputWriter.write("""{"jsonrpc":"2.0","id":"screen","method":"tools/call","params":{"name":"fixthis_get_current_screen","arguments":{}}}""")
            inputWriter.newLine()
            inputWriter.flush()
            withTimeout(1_000) { bridge.started.await() }

            inputWriter.close()

            withTimeout(1_000) { bridge.cancelled.await() }
            withTimeout(1_000) { job.join() }
            assertEquals("", stdout.toString().trim())
        } finally {
            inputWriter.close()
            input.close()
            job.cancelAndJoin()
        }
    }

    @Test
    fun initializeResponseIncludesCapabilities() {
        val response = runSingleRequest(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"test","version":"1"}}}""",
        )

        val result = response.jsonObject.getValue("result").jsonObject
        assertEquals("2.0", response.jsonObject.getValue("jsonrpc").jsonPrimitive.content)
        assertEquals("2025-06-18", result.getValue("protocolVersion").jsonPrimitive.content)
        assertNotNull(result.getValue("capabilities").jsonObject["tools"])
        assertNotNull(result.getValue("capabilities").jsonObject["resources"])
        assertFalse(result.getValue("capabilities").jsonObject.containsKey("prompts"))
        assertEquals("fixthis-mcp", result.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun toolsListIncludesFixThisTools() {
        val response = runSingleRequest("""{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""")

        val tools = response.jsonObject
            .getValue("result").jsonObject
            .getValue("tools").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }

        assertEquals(
            listOf(
                "fixthis_status",
                "fixthis_get_current_screen",
                "fixthis_verify_ui_change",
                "fixthis_open_feedback_console",
                "fixthis_list_feedback_sessions",
                "fixthis_capture_screen",
                "fixthis_navigate_app",
                "fixthis_list_feedback",
                "fixthis_read_feedback",
                "fixthis_resolve_feedback",
                "fixthis_claim_feedback",
            ),
            tools,
        )
    }

    @Test
    fun toolsListIncludesClaimFeedback() {
        val response = runSingleRequest("""{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""")

        val tools = response.jsonObject
            .getValue("result").jsonObject
            .getValue("tools").jsonArray
        val claim = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "fixthis_claim_feedback" }
        val description = claim.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(description, description.contains("before starting work", ignoreCase = true))
        assertTrue(description, description.contains("fixthis_resolve_feedback", ignoreCase = true))
        val inputSchema = claim.jsonObject["inputSchema"]!!.jsonObject
        val required = inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("itemId"), required)
    }

    @Test
    fun resolveFeedbackDescriptionMentionsClaimPairing() {
        val response = runSingleRequest("""{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""")

        val tools = response.jsonObject
            .getValue("result").jsonObject
            .getValue("tools").jsonArray
        val resolve = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "fixthis_resolve_feedback" }
        val description = resolve.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(description, description.contains("after claiming", ignoreCase = true))
    }

    @Test
    fun readFeedbackSchemaAdvertisesDetailMode() {
        val response = runSingleRequest("""{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""")

        val readFeedbackTool = response.jsonObject
            .getValue("result").jsonObject
            .getValue("tools").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "fixthis_read_feedback" }
        val detailMode = readFeedbackTool
            .getValue("inputSchema").jsonObject
            .getValue("properties").jsonObject
            .getValue("detailMode").jsonObject
        val enumValues = detailMode
            .getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }

        assertEquals(listOf("compact", "precise", "full"), enumValues)
        assertTrue(detailMode.getValue("description").jsonPrimitive.content.contains("JSON remains complete"))
    }

    @Test
    fun listFeedbackSessionsReturnsPersistedSummaries() = runBlocking {
        val projectRoot = tempDir(prefix = "fixthis-v2-mcp-sessions-")
        val tools = FixThisTools(
            bridge = FakeBridge(defaultPackageName = "io.beyondwin.fixthis.sample"),
            defaultPackageName = "io.beyondwin.fixthis.sample",
            projectRoot = projectRoot,
        )
        tools.call("fixthis_open_feedback_console", jsonObject("newSession" to true))

        val payload = tools.call("fixthis_list_feedback_sessions", JsonObject(emptyMap())).firstJsonContent()
        val sessions = payload.getValue("sessions").jsonArray

        assertEquals(projectRoot.absolutePath, payload.getValue("projectRoot").jsonPrimitive.content)
        assertEquals(1, sessions.size)
        assertEquals(
            "io.beyondwin.fixthis.sample",
            sessions[0].jsonObject.getValue("packageName").jsonPrimitive.content,
        )
    }

    @Test
    fun openFeedbackConsoleCanOpenExactSession() = runBlocking {
        val tools = FixThisTools(
            bridge = FakeBridge(defaultPackageName = "io.beyondwin.fixthis.sample"),
            defaultPackageName = "io.beyondwin.fixthis.sample",
            projectRoot = tempDir(prefix = "fixthis-v2-mcp-open-"),
        )
        val opened = tools.call("fixthis_open_feedback_console", jsonObject("newSession" to true)).firstJsonContent()
        val sessionId = opened.getValue("sessionId").jsonPrimitive.content

        val reopened = tools.call("fixthis_open_feedback_console", jsonObject("sessionId" to sessionId)).firstJsonContent()

        assertEquals(sessionId, reopened.getValue("sessionId").jsonPrimitive.content)
        assertEquals(true, reopened.getValue("resumed").jsonPrimitive.boolean)
    }

    @Test
    fun navigateAppPerformsBackAndCapturesResult() = runBlocking {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val tools = FixThisTools(
            bridge = bridge,
            defaultPackageName = "io.beyondwin.fixthis.sample",
            projectRoot = tempDir(prefix = "fixthis-v2-nav-mcp-"),
        )
        val opened = tools.call("fixthis_open_feedback_console", jsonObject("newSession" to true)).firstJsonContent()
        val sessionId = opened["sessionId"]!!.jsonPrimitive.content

        val result = tools.call(
            "fixthis_navigate_app",
            jsonObject("sessionId" to sessionId, "action" to "back", "captureAfter" to true),
        ).firstJsonContent()

        assertEquals(true, result["performed"]!!.jsonPrimitive.boolean)
        assertEquals("back", result["action"]!!.jsonPrimitive.content)
        assertEquals(1, bridge.navigationRequests.size)
        assertTrue(result["screen"] != null)
    }

    @Test
    fun captureScreenUpdatesLatestScreenshotResource() = runBlocking {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val tools = FixThisTools(
            bridge = bridge,
            defaultPackageName = "io.beyondwin.fixthis.sample",
            projectRoot = tempDir(prefix = "fixthis-v2-capture-resource-"),
        )
        val opened = tools.call("fixthis_open_feedback_console", jsonObject("newSession" to true)).firstJsonContent()
        val sessionId = opened["sessionId"]!!.jsonPrimitive.content

        val captured = tools.call("fixthis_capture_screen", jsonObject("sessionId" to sessionId)).firstJsonContent()
        val capturedPath = captured
            .getValue("screen").jsonObject
            .getValue("screenshot").jsonObject
            .getValue("desktopFullPath").jsonPrimitive.content
        val resource = tools.readResource("fixthis://screenshot/latest/full.png")
        val payload = parse(
            resource
                .getValue("contents").jsonArray[0].jsonObject
                .getValue("text").jsonPrimitive.content,
        ).jsonObject

        assertFalse(payload.containsKey("available"))
        assertEquals(capturedPath, payload.getValue("path").jsonPrimitive.content)
    }

    @Test
    fun navigateAppWithCaptureAfterFalseDoesNotCaptureScreen() = runBlocking {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val tools = FixThisTools(
            bridge = bridge,
            defaultPackageName = "io.beyondwin.fixthis.sample",
            projectRoot = tempDir(prefix = "fixthis-v2-nav-no-capture-"),
        )
        val opened = tools.call("fixthis_open_feedback_console", jsonObject("newSession" to true)).firstJsonContent()
        val sessionId = opened["sessionId"]!!.jsonPrimitive.content

        val result = tools.call(
            "fixthis_navigate_app",
            jsonObject("sessionId" to sessionId, "action" to "back", "captureAfter" to false),
        ).firstJsonContent()

        assertEquals(true, result["performed"]!!.jsonPrimitive.boolean)
        assertEquals(1, bridge.navigationRequests.size)
        assertFalse(result.containsKey("screen"))
        assertEquals(null, bridge.lastCaptureSessionId)
    }

    @Test
    fun navigateAppReturnsCaptureErrorWhenFollowUpCaptureFails() = runBlocking {
        val bridge = FakeFixThisBridge(
            packageName = "io.beyondwin.fixthis.sample",
            captureError = IllegalStateException("snapshot failed"),
        )
        val tools = FixThisTools(
            bridge = bridge,
            defaultPackageName = "io.beyondwin.fixthis.sample",
            projectRoot = tempDir(prefix = "fixthis-v2-nav-capture-fail-"),
        )
        val opened = tools.call("fixthis_open_feedback_console", jsonObject("newSession" to true)).firstJsonContent()
        val sessionId = opened["sessionId"]!!.jsonPrimitive.content

        val result = tools.call(
            "fixthis_navigate_app",
            jsonObject("sessionId" to sessionId, "action" to "back", "captureAfter" to true),
        ).firstJsonContent()

        assertEquals(true, result["performed"]!!.jsonPrimitive.boolean)
        assertEquals("snapshot failed", result["captureError"]!!.jsonPrimitive.content)
        assertFalse(result.containsKey("screen"))
        assertEquals(1, bridge.navigationRequests.size)
    }

    @Test
    fun navigateAppRejectsMissingActionWithoutCreatingSession() = runBlocking {
        val projectRoot = tempDir(prefix = "fixthis-v2-nav-missing-action-")
        val tools = FixThisTools(
            bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample"),
            defaultPackageName = "io.beyondwin.fixthis.sample",
            projectRoot = projectRoot,
        )

        val error = kotlin.runCatching {
            tools.call("fixthis_navigate_app", JsonObject(emptyMap()))
        }.exceptionOrNull()
        val listPayload = tools.call("fixthis_list_feedback_sessions", JsonObject(emptyMap())).firstJsonContent()

        assertTrue(error is io.beyondwin.fixthis.mcp.tools.FixThisToolException)
        assertTrue(error?.message.orEmpty().contains("requires action"))
        assertEquals(0, listPayload.getValue("sessions").jsonArray.size)
    }

    @Test
    fun navigateAppRejectsTapWithoutCoordinates() = runBlocking {
        val tools = FixThisTools(
            bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample"),
            defaultPackageName = "io.beyondwin.fixthis.sample",
            projectRoot = tempDir(prefix = "fixthis-v2-nav-invalid-"),
        )
        val opened = tools.call("fixthis_open_feedback_console", jsonObject("newSession" to true)).firstJsonContent()
        val sessionId = opened["sessionId"]!!.jsonPrimitive.content

        val error = kotlin.runCatching {
            tools.call(
                "fixthis_navigate_app",
                jsonObject("sessionId" to sessionId, "action" to "tap", "x" to 10),
            )
        }.exceptionOrNull()

        assertTrue(error is io.beyondwin.fixthis.mcp.tools.FixThisToolException)
        assertTrue(error?.message.orEmpty().contains("Tap navigation requires x and y"))
    }

    @Test
    fun feedbackSessionsPersistAcrossFixThisToolsInstances() = runBlocking {
        val projectRoot = tempDir(prefix = "fixthis-v2-mcp-persisted-")
        val firstTools = FixThisTools(
            bridge = FakeBridge(defaultPackageName = "io.beyondwin.fixthis.sample"),
            defaultPackageName = "io.beyondwin.fixthis.sample",
            projectRoot = projectRoot,
        )
        val opened = firstTools.call(
            "fixthis_open_feedback_console",
            jsonObject("newSession" to true),
        ).firstJsonContent()
        val sessionId = opened.getValue("sessionId").jsonPrimitive.content

        val secondTools = FixThisTools(
            bridge = FakeBridge(defaultPackageName = "io.beyondwin.fixthis.sample"),
            defaultPackageName = "io.beyondwin.fixthis.sample",
            projectRoot = projectRoot,
        )
        val listPayload = secondTools.call("fixthis_list_feedback_sessions", JsonObject(emptyMap())).firstJsonContent()
        val reopened = secondTools.call(
            "fixthis_open_feedback_console",
            jsonObject("sessionId" to sessionId),
        ).firstJsonContent()

        assertEquals(listOf(sessionId), listPayload.getValue("sessions").jsonArray.map {
            it.jsonObject.getValue("sessionId").jsonPrimitive.content
        })
        assertEquals(sessionId, reopened.getValue("sessionId").jsonPrimitive.content)
        assertEquals(true, reopened.getValue("resumed").jsonPrimitive.boolean)
    }

    @Test
    fun listFeedbackReturnsCurrentSessionQueue() {
        val bridge = FakeBridge(defaultPackageName = "io.beyondwin.fixthis.sample")
        val server = server(
            bridge,
            defaultPackageName = "io.beyondwin.fixthis.sample",
            projectRoot = File("/repo"),
        )

        runToolCall(
            server,
            "fixthis_open_feedback_console",
            """{}""",
        )
        val payload = runToolCall(
            server,
            "fixthis_list_feedback",
            """{}""",
        )

        assertTrue(payload.containsKey("sessionId"))
        assertEquals("io.beyondwin.fixthis.sample", payload.getValue("packageName").jsonPrimitive.content)
    }

    @Test
    fun listFeedbackIncludesDraftAndSentCounts() = runBlocking {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val projectRoot = tempDir(prefix = "fixthis-handoff-list-")
        val service = feedbackService(
            bridge,
            "session-1",
            "screen-1",
            "item-1",
            "item-2",
            "item-3",
            "item-4",
            "batch-1",
            "item-5",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.captureScreen(session.sessionId)
        val readyItem = service.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(1f, 1f, 2f, 2f),
            "Ready sent item",
        )
        val needsClarificationItem = service.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(2f, 2f, 3f, 3f),
            "Needs clarification sent item",
        )
        val resolvedItem = service.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(3f, 3f, 4f, 4f),
            "Resolved sent item",
        )
        val wontFixItem = service.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(4f, 4f, 5f, 5f),
            "Won't fix sent item",
        )
        service.sendDraftToAgent(session.sessionId)
        service.resolveFeedback(session.sessionId, needsClarificationItem.itemId, AnnotationStatusDto.NEEDS_CLARIFICATION, null)
        service.resolveFeedback(session.sessionId, resolvedItem.itemId, AnnotationStatusDto.RESOLVED, null)
        service.resolveFeedback(session.sessionId, wontFixItem.itemId, AnnotationStatusDto.WONT_FIX, null)
        service.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(5f, 5f, 6f, 6f),
            "Draft item",
        )
        val tools = FixThisTools(
            bridge = bridge,
            projectRoot = projectRoot,
            defaultPackageName = "io.beyondwin.fixthis.sample",
            feedbackService = service,
        )

        val result = tools.call("fixthis_list_feedback", JsonObject(emptyMap())).firstJsonContent()

        assertEquals(1, result.getValue("draftItemsCount").jsonPrimitive.int)
        assertEquals(1, result.getValue("sentBatchesCount").jsonPrimitive.int)
        assertEquals(2, result.getValue("unresolvedSentItemsCount").jsonPrimitive.int)
        assertEquals(readyItem.itemId, result.getValue("items").jsonArray[0].jsonObject.getValue("itemId").jsonPrimitive.content)
    }

    @Test
    fun readFeedbackWithItemIdFiltersOutUnrelatedHandoffBatches() = runBlocking {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val service = feedbackService(
            bridge,
            "session-1",
            "screen-1",
            "item-1",
            "batch-1",
            "item-2",
            "batch-2",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.captureScreen(session.sessionId)
        service.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(1f, 1f, 2f, 2f),
            "First batch feedback",
        )
        service.sendDraftToAgent(session.sessionId)
        val secondItem = service.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(2f, 2f, 3f, 3f),
            "Second batch feedback",
        )
        service.sendDraftToAgent(session.sessionId)
        val server = server(bridge, feedbackService = service)

        val content = runToolCallContentTexts(
            server,
            "fixthis_read_feedback",
            """{"sessionId":"${session.sessionId}","itemId":"${secondItem.itemId}"}""",
        )
        val payload = parse(content[0]).jsonObject
        val items = payload.getValue("items").jsonArray
        val handoffBatches = payload.getValue("handoffBatches").jsonArray
        val handoffBatch = handoffBatches.single().jsonObject
        val handoffBatchItemIds = handoffBatch.getValue("itemIds").jsonArray
            .map { it.jsonPrimitive.content }

        assertEquals(1, items.size)
        assertEquals(secondItem.itemId, items[0].jsonObject.getValue("itemId").jsonPrimitive.content)
        assertEquals(1, handoffBatches.size)
        assertEquals(2, handoffBatch.getValue("sequenceNumber").jsonPrimitive.int)
        assertEquals(listOf(secondItem.itemId), handoffBatchItemIds)
        assertTrue(content[1].contains("Second batch feedback"))
        assertFalse(content[1].contains("Batch #1"))
        assertFalse(content[1].contains("Batch #2"))
        assertFalse(content[1].contains("Sent History"))
        assertFalse(content[1].contains("First batch feedback"))
    }

    @Test
    fun readAndResolveFeedbackHonorExplicitSessionId() = runBlocking {
        val bridge = FakeBridge(defaultPackageName = "com.default")
        val service = feedbackService(
            bridge,
            "session-a",
            "screen-a",
            "item-a",
            "session-b",
            "screen-b",
            "item-b",
        )
        val sessionA = service.openSession("com.first")
        val screenA = service.captureScreen(sessionA.sessionId)
        val itemA = service.addAreaFeedback(
            sessionA.sessionId,
            screenA.screenId,
            FixThisRect(1f, 2f, 3f, 4f),
            "First session feedback",
        )
        service.openSession("com.second").also { sessionB ->
            val screenB = service.captureScreen(sessionB.sessionId)
            service.addAreaFeedback(
                sessionB.sessionId,
                screenB.screenId,
                FixThisRect(5f, 6f, 7f, 8f),
                "Second session feedback",
            )
        }
        val server = server(bridge, feedbackService = service)

        val readPayload = runToolCall(
            server,
            "fixthis_read_feedback",
            """{"sessionId":"${sessionA.sessionId}"}""",
        )
        val resolvedPayload = runToolCall(
            server,
            "fixthis_resolve_feedback",
            """{"sessionId":"${sessionA.sessionId}","itemId":"${itemA.itemId}","status":"resolved","summary":"Fixed"}""",
        )

        assertEquals("com.first", readPayload.getValue("packageName").jsonPrimitive.content)
        assertEquals("resolved", resolvedPayload.getValue("status").jsonPrimitive.content)
        assertEquals("Fixed", resolvedPayload.getValue("agentSummary").jsonPrimitive.content)
    }

    @Test
    fun readFeedbackWithItemIdFiltersOutUnrelatedFeedback() = runBlocking {
        val bridge = FakeBridge(defaultPackageName = "com.default")
        val service = feedbackService(bridge, "session-1", "screen-1", "item-1", "item-2")
        val session = service.openSession("com.first")
        val screen = service.captureScreen(session.sessionId)
        val firstItem = service.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(1f, 2f, 3f, 4f),
            "Keep this feedback",
        )
        service.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(5f, 6f, 7f, 8f),
            "Filter this feedback",
        )
        val server = server(bridge, feedbackService = service)

        val content = runToolCallContentTexts(
            server,
            "fixthis_read_feedback",
            """{"sessionId":"${session.sessionId}","itemId":"${firstItem.itemId}"}""",
        )
        val payload = parse(content[0]).jsonObject
        val items = payload.getValue("items").jsonArray
        val screens = payload.getValue("screens").jsonArray

        assertEquals(1, items.size)
        assertEquals(firstItem.itemId, items[0].jsonObject.getValue("itemId").jsonPrimitive.content)
        assertEquals(1, screens.size)
        assertTrue(content[1].contains("Keep this feedback"))
        assertFalse(content[1].contains("Filter this feedback"))
    }

    @Test
    fun resolveFeedbackRejectsInvalidStatus() = runBlocking {
        val bridge = FakeBridge(defaultPackageName = "com.default")
        val service = feedbackService(bridge, "session-1", "screen-1", "item-1")
        val session = service.openSession("com.first")
        val screen = service.captureScreen(session.sessionId)
        val item = service.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(1f, 2f, 3f, 4f),
            "Needs resolution",
        )
        val server = server(bridge, feedbackService = service)

        val error = runToolCallError(
            server,
            "fixthis_resolve_feedback",
            """{"sessionId":"${session.sessionId}","itemId":"${item.itemId}","status":"open"}""",
        )

        assertEquals(-32602, error.getValue("code").jsonPrimitive.int)
        assertTrue(error.getValue("message").jsonPrimitive.content.contains("Unsupported feedback resolution status: open"))
    }

    @Test
    fun callClaimFeedbackReturnsUpdatedItem() = runBlocking {
        val bridge = FakeBridge(defaultPackageName = "com.default")
        val service = feedbackService(bridge, "session-1", "screen-1", "item-1")
        val session = service.openSession("com.first")
        val screen = service.captureScreen(session.sessionId)
        val item = service.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(1f, 2f, 3f, 4f),
            "Needs work",
        )
        val server = server(bridge, feedbackService = service)

        val payload = runToolCall(
            server,
            "fixthis_claim_feedback",
            """{"sessionId":"${session.sessionId}","itemId":"${item.itemId}","agentNote":"starting"}""",
        )

        assertEquals("in_progress", payload.getValue("status").jsonPrimitive.content)
        assertEquals("starting", payload.getValue("agentSummary").jsonPrimitive.content)
    }

    @Test
    fun callClaimFeedbackRequiresItemId() = runBlocking {
        val bridge = FakeBridge(defaultPackageName = "com.default")
        val service = feedbackService(bridge, "session-1", "screen-1", "item-1")
        val session = service.openSession("com.first")
        service.captureScreen(session.sessionId)
        val server = server(bridge, feedbackService = service)

        val error = runToolCallError(
            server,
            "fixthis_claim_feedback",
            """{"sessionId":"${session.sessionId}"}""",
        )

        assertTrue(
            error.getValue("message").jsonPrimitive.content,
            error.getValue("message").jsonPrimitive.content.contains("requires itemId", ignoreCase = true),
        )
    }

    @Test
    fun verifyUiChangeSchemaMarksExpectedTextRequired() {
        val response = runSingleRequest("""{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""")

        val verifyTool = response.jsonObject
            .getValue("result").jsonObject
            .getValue("tools").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "fixthis_verify_ui_change" }
        val required = verifyTool
            .getValue("inputSchema").jsonObject
            .getValue("required").jsonArray
            .map { it.jsonPrimitive.content }

        assertEquals(listOf("expectedText"), required)
    }

    @Test
    fun verifyUiChangeReturnsNormalizedFoundAndMatchingNodesPayload() {
        val bridge = FakeBridge()
        val payload = runToolCall(
            server(bridge),
            "fixthis_verify_ui_change",
            """{"packageName":"io.beyondwin.fixthis.sample","expectedText":"Pay now","role":"Button"}""",
        )

        assertEquals(listOf("verifyUiChange:io.beyondwin.fixthis.sample:Pay now:Button"), bridge.calls)
        assertEquals(true, payload.getValue("found").jsonPrimitive.boolean)
        val matches = payload.getValue("matchingNodes").jsonArray
        assertEquals(1, matches.size)
        assertEquals("Pay now", matches[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("Button", matches[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals(
            true,
            payload.getValue("bridge").jsonObject.getValue("verified").jsonPrimitive.boolean,
        )
        assertFalse(payload.containsKey("verified"))
        assertFalse(payload.containsKey("matchedText"))
    }

    @Test
    fun verifyUiChangeNormalizesMissingMatchToEmptyArray() {
        val payload = runToolCall(
            server(FakeBridge(verificationMatches = false)),
            "fixthis_verify_ui_change",
            """{"expectedText":"Refund now"}""",
        )

        assertEquals(false, payload.getValue("found").jsonPrimitive.boolean)
        assertTrue(payload.getValue("matchingNodes").jsonArray.isEmpty())
        assertEquals(
            "Expected text was not found on the current screen",
            payload.getValue("bridge").jsonObject.getValue("message").jsonPrimitive.content,
        )
    }

    @Test
    fun diagnosticsAreWrittenToStderrNotStdout() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        runBlocking {
            server().run(
                input = ByteArrayInputStream("""{"jsonrpc":"2.0","id":2,"method":"ping","params":{}}""".toByteArray()),
                output = stdout,
                diagnostics = stderr,
            )
        }

        val stdoutLines = stdout.toString().trim().lines()
        assertEquals(1, stdoutLines.size)
        assertEquals(2, parse(stdoutLines.single()).jsonObject.getValue("id").jsonPrimitive.int)
        assertTrue(stderr.toString().contains("FixThis MCP server started"))
    }

    @Test
    fun invalidToolParamsReturnJsonRpcError() {
        val response = runSingleRequest("""{"jsonrpc":"2.0","id":"bad","method":"tools/call","params":{"arguments":{}}}""")

        val error = response.jsonObject.getValue("error").jsonObject
        assertEquals(-32602, error.getValue("code").jsonPrimitive.int)
        assertTrue(error.getValue("message").jsonPrimitive.content.contains("name"))
    }

    @Test
    fun currentScreenWithExplicitPackageDoesNotRequireDefaultPackageMetadata() {
        val response = runSingleRequest(
            """{"jsonrpc":"2.0","id":"tool","method":"tools/call","params":{"name":"fixthis_get_current_screen","arguments":{"packageName":"com.explicit"}}}""",
            bridge = FakeBridge(defaultPackageName = null),
        )

        val result = response.jsonObject.getValue("result").jsonObject
        assertFalse(result.getValue("isError").jsonPrimitive.boolean)
        val payload = parse(
            result
                .getValue("content").jsonArray[0].jsonObject
                .getValue("text").jsonPrimitive.content,
        ).jsonObject

        assertEquals("com.explicit", payload.getValue("screen").jsonObject.getValue("packageName").jsonPrimitive.content)
        assertFalse(payload.containsKey("screenshotResource"))
    }

    @Test
    fun openFeedbackConsoleWithExplicitPackageDoesNotRequireDefaultPackageMetadata() {
        val response = runSingleRequest(
            """{"jsonrpc":"2.0","id":"tool","method":"tools/call","params":{"name":"fixthis_open_feedback_console","arguments":{"packageName":"com.explicit"}}}""",
            bridge = FakeBridge(defaultPackageName = null),
        )
        val result = response.jsonObject.getValue("result").jsonObject
        assertFalse(result.getValue("isError").jsonPrimitive.boolean)
        val payload = parse(
            result
                .getValue("content").jsonArray[0].jsonObject
                .getValue("text").jsonPrimitive.content,
        ).jsonObject

        assertEquals("com.explicit", payload.getValue("packageName").jsonPrimitive.content)
        assertTrue(payload.getValue("consoleUrl").jsonPrimitive.content.startsWith("http://127.0.0.1:"))
    }

    @Test
    fun stdioCancellationNotificationIsProcessedWhileToolCallIsPending() = runBlocking {
        val bridge = BlockingScreenToolBridge()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val input = PipedInputStream()
        val inputWriter = PipedOutputStream(input).bufferedWriter(Charsets.UTF_8)
        val job = launch(Dispatchers.IO) {
            server(bridge).run(input = input, output = stdout, diagnostics = stderr)
        }

        try {
            inputWriter.write("""{"jsonrpc":"2.0","id":"screen","method":"tools/call","params":{"name":"fixthis_get_current_screen","arguments":{}}}""")
            inputWriter.newLine()
            inputWriter.flush()
            withTimeout(1_000) { bridge.started.await() }

            inputWriter.write("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"screen","reason":"test cancellation"}}""")
            inputWriter.newLine()
            inputWriter.write("""{"jsonrpc":"2.0","id":"ping-after-cancel","method":"ping","params":{}}""")
            inputWriter.newLine()
            inputWriter.flush()

            withTimeout(1_000) { bridge.cancelled.await() }
            withTimeout(1_000) {
                while (!stdout.toString().contains("ping-after-cancel")) {
                    delay(10)
                }
            }

            val lines = stdout.toString().trim().lines().filter { it.isNotBlank() }
            assertEquals(listOf("ping-after-cancel"), lines.map { parse(it).jsonObject.getValue("id").jsonPrimitive.content })
        } finally {
            inputWriter.close()
            input.close()
            job.cancelAndJoin()
        }
    }

    @Test
    fun stdinEofCancelsPendingToolCallsAndReturnsPromptly() = runBlocking {
        val bridge = BlockingScreenToolBridge()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val input = PipedInputStream()
        val inputWriter = PipedOutputStream(input).bufferedWriter(Charsets.UTF_8)
        val job = launch(Dispatchers.IO) {
            server(bridge).run(input = input, output = stdout, diagnostics = stderr)
        }

        inputWriter.write("""{"jsonrpc":"2.0","id":"screen","method":"tools/call","params":{"name":"fixthis_get_current_screen","arguments":{}}}""")
        inputWriter.newLine()
        inputWriter.flush()
        withTimeout(1_000) { bridge.started.await() }

        inputWriter.close()

        withTimeout(1_000) { bridge.cancelled.await() }
        withTimeout(1_000) { job.join() }
        assertEquals("", stdout.toString().trim())
    }

    @Test
    fun stdioCancellationNotificationIsProcessedWhileResourceReadIsPending() = runBlocking {
        val bridge = BlockingResourceBridge()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val input = PipedInputStream()
        val inputWriter = PipedOutputStream(input).bufferedWriter(Charsets.UTF_8)
        val job = launch(Dispatchers.IO) {
            server(bridge).run(input = input, output = stdout, diagnostics = stderr)
        }

        try {
            inputWriter.write("""{"jsonrpc":"2.0","id":"screen","method":"resources/read","params":{"uri":"fixthis://screen/current"}}""")
            inputWriter.newLine()
            inputWriter.flush()
            withTimeout(1_000) { bridge.started.await() }

            inputWriter.write("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"screen","reason":"test cancellation"}}""")
            inputWriter.newLine()
            inputWriter.write("""{"jsonrpc":"2.0","id":"ping-after-resource-cancel","method":"ping","params":{}}""")
            inputWriter.newLine()
            inputWriter.flush()

            withTimeout(1_000) { bridge.cancelled.await() }
            withTimeout(1_000) {
                while (!stdout.toString().contains("ping-after-resource-cancel")) {
                    delay(10)
                }
            }

            val lines = stdout.toString().trim().lines().filter { it.isNotBlank() }
            assertEquals(listOf("ping-after-resource-cancel"), lines.map { parse(it).jsonObject.getValue("id").jsonPrimitive.content })
        } finally {
            inputWriter.close()
            input.close()
            job.cancelAndJoin()
        }
    }

    @Test
    fun stdinEofCancelsPendingResourceReadsAndReturnsPromptly() = runBlocking {
        val bridge = BlockingResourceBridge()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val input = PipedInputStream()
        val inputWriter = PipedOutputStream(input).bufferedWriter(Charsets.UTF_8)
        val job = launch(Dispatchers.IO) {
            server(bridge).run(input = input, output = stdout, diagnostics = stderr)
        }

        inputWriter.write("""{"jsonrpc":"2.0","id":"screen","method":"resources/read","params":{"uri":"fixthis://screen/current"}}""")
        inputWriter.newLine()
        inputWriter.flush()
        withTimeout(1_000) { bridge.started.await() }

        inputWriter.close()

        withTimeout(1_000) { bridge.cancelled.await() }
        withTimeout(1_000) { job.join() }
        assertEquals("", stdout.toString().trim())
    }

    @Test
    fun invalidJsonRpcEnvelopesReturnInvalidRequestErrors() {
        listOf(
            """{"id":1,"method":"ping","params":{}}""",
            """{"jsonrpc":"1.0","id":1,"method":"ping","params":{}}""",
            """{"jsonrpc":"2.0","id":null,"method":"ping","params":{}}""",
            """{"jsonrpc":"2.0","id":{},"method":"ping","params":{}}""",
            """{"jsonrpc":"2.0","id":1,"method":42,"params":{}}""",
            """{"jsonrpc":"2.0","id":1,"method":"notifications/progress","params":{}}""",
        ).forEach { request ->
            val error = runSingleRequest(request).jsonObject.getValue("error").jsonObject
            assertEquals(-32600, error.getValue("code").jsonPrimitive.int)
        }
    }

    @Test
    fun parseErrorsReturnJsonRpcParseError() {
        val error = runSingleRequest("""{"jsonrpc":"2.0","id":1,"method":"ping"""")
            .jsonObject.getValue("error").jsonObject

        assertEquals(-32700, error.getValue("code").jsonPrimitive.int)
    }

    private fun runToolCall(server: McpServer, name: String, argumentsJson: String): JsonObject {
        return parse(runToolCallContentTexts(server, name, argumentsJson)[0]).jsonObject
    }

    private fun JsonObject.firstJsonContent(): JsonObject =
        parse(
            getValue("content").jsonArray[0].jsonObject
                .getValue("text").jsonPrimitive.content,
        ).jsonObject

    private fun jsonObject(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is Boolean -> put(key, value)
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is JsonElement -> put(key, value)
                else -> error("Unsupported JSON helper value for $key: ${value::class.java.simpleName}")
            }
        }
    }

    private fun runToolCallContentTexts(server: McpServer, name: String, argumentsJson: String): List<String> {
        val response = runSingleRequest(
            """{"jsonrpc":"2.0","id":"tool","method":"tools/call","params":{"name":"$name","arguments":$argumentsJson}}""",
            server = server,
        )
        return response.jsonObject
            .getValue("result").jsonObject
            .getValue("content").jsonArray
            .map { it.jsonObject.getValue("text").jsonPrimitive.content }
    }

    private fun runToolCallError(server: McpServer, name: String, argumentsJson: String): JsonObject {
        val response = runSingleRequest(
            """{"jsonrpc":"2.0","id":"tool","method":"tools/call","params":{"name":"$name","arguments":$argumentsJson}}""",
            server = server,
        )
        return response.jsonObject.getValue("error").jsonObject
    }

    private fun runCompatibilityTranscript(
        vararg requests: String,
        server: McpServer = server(),
    ): List<kotlinx.serialization.json.JsonElement> {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val input = requests.joinToString(separator = "\n", postfix = "\n")
        runBlocking {
            server.run(
                input = ByteArrayInputStream(input.toByteArray()),
                output = stdout,
                diagnostics = stderr,
            )
        }
        return stdout.toString()
            .lines()
            .filter { it.isNotBlank() }
            .map(::parse)
    }

    private fun runSingleRequest(
        request: String,
        bridge: FixThisBridge = FakeBridge(),
        server: McpServer = server(bridge),
    ): kotlinx.serialization.json.JsonElement {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        runBlocking {
            server.run(
                input = ByteArrayInputStream((request + "\n").toByteArray()),
                output = stdout,
                diagnostics = stderr,
            )
        }
        return parse(stdout.toString().trim().lines().single())
    }

    private fun server(
        bridge: FixThisBridge = FakeBridge(),
        defaultPackageName: String? = null,
        projectRoot: File = File(".").canonicalFile,
        feedbackService: FeedbackSessionService = FeedbackSessionService(
            bridge = bridge,
            projectRoot = projectRoot.absolutePath,
            defaultPackageName = defaultPackageName,
        ),
    ): McpServer =
        McpServer(protocol = McpProtocol(tools = FixThisTools(bridge, defaultPackageName, projectRoot, feedbackService)))

    private fun parse(value: String) = McpProtocol.json.parseToJsonElement(value)

    private fun feedbackService(bridge: FixThisBridge, vararg ids: String): FeedbackSessionService =
        FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds(*ids).next),
            projectRoot = "/repo",
            defaultPackageName = "com.default",
        )

    private class FakeIds(vararg values: String) {
        private val queue = ArrayDeque(values.toList())
        val next: () -> String = { queue.removeFirst() }
    }

    private class FakeBridge(
        private val defaultPackageName: String? = "io.beyondwin.fixthis.sample",
        private val verificationMatches: Boolean = true,
    ) : FixThisBridge {
        val calls = mutableListOf<String>()

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride?.takeIf { it.isNotBlank() }
                ?: defaultPackageName
                ?: error("No default package metadata is available")

        override suspend fun status(packageName: String): JsonObject {
            calls += "status:$packageName"
            return buildJsonObject {
                put("activity", "$packageName.MainActivity")
                put("rootsCount", 1)
                put("sidekickVersion", "1.0")
                put("bridgeProtocolVersion", "1.0")
                put("sourceIndexAvailable", true)
            }
        }

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject {
            calls += "inspectCurrentScreen:$packageName"
            return buildJsonObject {
                put("packageName", packageName)
                put("activity", "$packageName.MainActivity")
                put("roots", JsonArray(emptyList()))
            }
        }

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject {
            calls += "verifyUiChange:$packageName:$expectedText:${role.orEmpty()}"
            return buildJsonObject {
                put("verified", verificationMatches)
                put("expectedText", expectedText)
                if (verificationMatches) {
                    put("matchedText", expectedText)
                } else {
                    put("message", "Expected text was not found on the current screen")
                }
            }
        }

        override suspend fun performNavigation(packageName: String, request: FeedbackNavigationRequest): JsonObject {
            calls += "performNavigation:$packageName:${request.action}"
            return buildJsonObject {
                put("performed", true)
                put("activity", "$packageName.MainActivity")
            }
        }

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = JsonObject(emptyMap())

    }

    private class BlockingScreenToolBridge : FixThisBridge {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride?.takeIf { it.isNotBlank() } ?: "io.beyondwin.fixthis.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject {
            started.complete(Unit)
            return try {
                delay(Long.MAX_VALUE)
                JsonObject(emptyMap())
            } catch (error: CancellationException) {
                cancelled.complete(Unit)
                throw error
            }
        }

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
            JsonObject(emptyMap())

        override suspend fun performNavigation(packageName: String, request: FeedbackNavigationRequest): JsonObject =
            JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = JsonObject(emptyMap())
    }

    private class BlockingResourceBridge : FixThisBridge {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride?.takeIf { it.isNotBlank() } ?: "io.beyondwin.fixthis.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject {
            started.complete(Unit)
            return try {
                delay(Long.MAX_VALUE)
                JsonObject(emptyMap())
            } catch (error: CancellationException) {
                cancelled.complete(Unit)
                throw error
            }
        }

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
            JsonObject(emptyMap())

        override suspend fun performNavigation(packageName: String, request: FeedbackNavigationRequest): JsonObject =
            JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = JsonObject(emptyMap())
    }

    private fun tempDir(prefix: String): File =
        kotlin.io.path.createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }
}
