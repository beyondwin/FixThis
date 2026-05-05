package io.github.pointpatch.mcp

import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.mcp.session.FeedbackSessionService
import io.github.pointpatch.mcp.session.FeedbackSessionStore
import io.github.pointpatch.mcp.tools.PointPatchBridge
import io.github.pointpatch.mcp.tools.PointPatchTools
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
        assertEquals("pointpatch-mcp", result.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun toolsListIncludesPointPatchTools() {
        val response = runSingleRequest("""{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""")

        val tools = response.jsonObject
            .getValue("result").jsonObject
            .getValue("tools").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }

        assertEquals(
            listOf(
                "pointpatch_status",
                "pointpatch_get_current_screen",
                "pointpatch_get_ui_feedback",
                "pointpatch_verify_ui_change",
                "pointpatch_open_feedback_console",
                "pointpatch_list_feedback_sessions",
                "pointpatch_capture_screen",
                "pointpatch_list_feedback",
                "pointpatch_read_feedback",
                "pointpatch_resolve_feedback",
            ),
            tools,
        )
    }

    @Test
    fun listFeedbackSessionsReturnsPersistedSummaries() = runBlocking {
        val projectRoot = createTempDir(prefix = "pointpatch-v2-mcp-sessions-")
        val tools = PointPatchTools(
            bridge = FakeBridge(defaultPackageName = "io.github.pointpatch.sample"),
            defaultPackageName = "io.github.pointpatch.sample",
            projectRoot = projectRoot,
        )
        tools.call("pointpatch_open_feedback_console", jsonObject("newSession" to true))

        val payload = tools.call("pointpatch_list_feedback_sessions", JsonObject(emptyMap())).firstJsonContent()
        val sessions = payload.getValue("sessions").jsonArray

        assertEquals(projectRoot.absolutePath, payload.getValue("projectRoot").jsonPrimitive.content)
        assertEquals(1, sessions.size)
        assertEquals(
            "io.github.pointpatch.sample",
            sessions[0].jsonObject.getValue("packageName").jsonPrimitive.content,
        )
    }

    @Test
    fun openFeedbackConsoleCanOpenExactSession() = runBlocking {
        val tools = PointPatchTools(
            bridge = FakeBridge(defaultPackageName = "io.github.pointpatch.sample"),
            defaultPackageName = "io.github.pointpatch.sample",
            projectRoot = createTempDir(prefix = "pointpatch-v2-mcp-open-"),
        )
        val opened = tools.call("pointpatch_open_feedback_console", jsonObject("newSession" to true)).firstJsonContent()
        val sessionId = opened.getValue("sessionId").jsonPrimitive.content

        val reopened = tools.call("pointpatch_open_feedback_console", jsonObject("sessionId" to sessionId)).firstJsonContent()

        assertEquals(sessionId, reopened.getValue("sessionId").jsonPrimitive.content)
        assertEquals(true, reopened.getValue("resumed").jsonPrimitive.boolean)
    }

    @Test
    fun feedbackSessionsPersistAcrossPointPatchToolsInstances() = runBlocking {
        val projectRoot = createTempDir(prefix = "pointpatch-v2-mcp-persisted-")
        val firstTools = PointPatchTools(
            bridge = FakeBridge(defaultPackageName = "io.github.pointpatch.sample"),
            defaultPackageName = "io.github.pointpatch.sample",
            projectRoot = projectRoot,
        )
        val opened = firstTools.call(
            "pointpatch_open_feedback_console",
            jsonObject("newSession" to true),
        ).firstJsonContent()
        val sessionId = opened.getValue("sessionId").jsonPrimitive.content

        val secondTools = PointPatchTools(
            bridge = FakeBridge(defaultPackageName = "io.github.pointpatch.sample"),
            defaultPackageName = "io.github.pointpatch.sample",
            projectRoot = projectRoot,
        )
        val listPayload = secondTools.call("pointpatch_list_feedback_sessions", JsonObject(emptyMap())).firstJsonContent()
        val reopened = secondTools.call(
            "pointpatch_open_feedback_console",
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
        val bridge = FakeBridge(defaultPackageName = "io.github.pointpatch.sample")
        val server = server(
            bridge,
            defaultPackageName = "io.github.pointpatch.sample",
            projectRoot = File("/repo"),
        )

        runToolCall(
            server,
            "pointpatch_open_feedback_console",
            """{}""",
        )
        val payload = runToolCall(
            server,
            "pointpatch_list_feedback",
            """{}""",
        )

        assertTrue(payload.containsKey("sessionId"))
        assertEquals("io.github.pointpatch.sample", payload.getValue("packageName").jsonPrimitive.content)
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
            PointPatchRect(1f, 2f, 3f, 4f),
            "First session feedback",
        )
        service.openSession("com.second").also { sessionB ->
            val screenB = service.captureScreen(sessionB.sessionId)
            service.addAreaFeedback(
                sessionB.sessionId,
                screenB.screenId,
                PointPatchRect(5f, 6f, 7f, 8f),
                "Second session feedback",
            )
        }
        val server = server(bridge, feedbackService = service)

        val readPayload = runToolCall(
            server,
            "pointpatch_read_feedback",
            """{"sessionId":"${sessionA.sessionId}"}""",
        )
        val resolvedPayload = runToolCall(
            server,
            "pointpatch_resolve_feedback",
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
            PointPatchRect(1f, 2f, 3f, 4f),
            "Keep this feedback",
        )
        service.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            PointPatchRect(5f, 6f, 7f, 8f),
            "Filter this feedback",
        )
        val server = server(bridge, feedbackService = service)

        val content = runToolCallContentTexts(
            server,
            "pointpatch_read_feedback",
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
            PointPatchRect(1f, 2f, 3f, 4f),
            "Needs resolution",
        )
        val server = server(bridge, feedbackService = service)

        val error = runToolCallError(
            server,
            "pointpatch_resolve_feedback",
            """{"sessionId":"${session.sessionId}","itemId":"${item.itemId}","status":"open"}""",
        )

        assertEquals(-32602, error.getValue("code").jsonPrimitive.int)
        assertTrue(error.getValue("message").jsonPrimitive.content.contains("Unsupported feedback resolution status: open"))
    }

    @Test
    fun verifyUiChangeSchemaMarksExpectedTextRequired() {
        val response = runSingleRequest("""{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""")

        val verifyTool = response.jsonObject
            .getValue("result").jsonObject
            .getValue("tools").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "pointpatch_verify_ui_change" }
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
            "pointpatch_verify_ui_change",
            """{"packageName":"io.github.pointpatch.sample","expectedText":"Pay now","role":"Button"}""",
        )

        assertEquals(listOf("verifyUiChange:io.github.pointpatch.sample:Pay now:Button"), bridge.calls)
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
            "pointpatch_verify_ui_change",
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
        assertTrue(stderr.toString().contains("PointPatch MCP server started"))
    }

    @Test
    fun invalidToolParamsReturnJsonRpcError() {
        val response = runSingleRequest("""{"jsonrpc":"2.0","id":"bad","method":"tools/call","params":{"arguments":{}}}""")

        val error = response.jsonObject.getValue("error").jsonObject
        assertEquals(-32602, error.getValue("code").jsonPrimitive.int)
        assertTrue(error.getValue("message").jsonPrimitive.content.contains("name"))
    }

    @Test
    fun getUiFeedbackReturnsAnnotationAndMarkdownFromBridgeCapture() {
        val bridge = FakeBridge()
        val response = runSingleRequest(
            """{"jsonrpc":"2.0","id":"feedback","method":"tools/call","params":{"name":"pointpatch_get_ui_feedback","arguments":{"packageName":"io.github.pointpatch.sample","timeoutMs":1500}}}""",
            bridge = bridge,
        )

        assertEquals(listOf("startFeedbackCapture:io.github.pointpatch.sample:1500"), bridge.calls)
        val content = response.jsonObject
            .getValue("result").jsonObject
            .getValue("content").jsonArray
            .map { it.jsonObject.getValue("text").jsonPrimitive.content }

        assertEquals("annotation-1", parse(content[0]).jsonObject.getValue("id").jsonPrimitive.content)
        assertFalse(content[0].contains("timeoutMillis"))
        assertTrue(content[1].contains("# PointPatch Compose Feedback"))
        assertTrue(response.jsonObject.getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean.not())
    }

    @Test
    fun oldGetUiFeedbackStillReturnsAnnotationAndMarkdown() = runBlocking {
        val response = runSingleRequest(
            """{"jsonrpc":"2.0","id":"feedback","method":"tools/call","params":{"name":"pointpatch_get_ui_feedback","arguments":{"timeoutMs":1500}}}""",
        )

        assertTrue(response.toString().contains("application/json"))
        assertTrue(response.toString().contains("text/markdown"))
    }

    @Test
    fun getUiFeedbackReturnsUnavailableJsonAndMarkdownWhenCaptureHasNoAnnotation() {
        val bridge = FakeBridge(annotationEnabled = false)
        val response = runSingleRequest(
            """{"jsonrpc":"2.0","id":"feedback","method":"tools/call","params":{"name":"pointpatch_get_ui_feedback","arguments":{"timeoutMs":1500}}}""",
            bridge = bridge,
        )

        val content = response.jsonObject
            .getValue("result").jsonObject
            .getValue("content").jsonArray
            .map { it.jsonObject.getValue("text").jsonPrimitive.content }
        val unavailable = parse(content[0]).jsonObject

        assertEquals(false, unavailable.getValue("available").jsonPrimitive.boolean)
        assertTrue(unavailable.getValue("message").jsonPrimitive.content.contains("annotation"))
        assertTrue(content[1].contains("did not return an annotation"))
    }

    @Test
    fun resourcesUseDefaultPackageScopedCacheInsteadOfLatestOverridePackageArtifacts() {
        val bridge = FakeBridge(defaultPackageName = "com.default")
        val server = server(bridge, defaultPackageName = "com.default")

        runSingleRequest(
            """{"jsonrpc":"2.0","id":"feedback","method":"tools/call","params":{"name":"pointpatch_get_ui_feedback","arguments":{"packageName":"com.override","timeoutMs":1500}}}""",
            server = server,
        )

        val annotationResponse = runSingleRequest(
            """{"jsonrpc":"2.0","id":"annotation","method":"resources/read","params":{"uri":"pointpatch://annotation/latest"}}""",
            server = server,
        )
        val annotation = parse(
            annotationResponse.jsonObject
                .getValue("result").jsonObject
                .getValue("contents").jsonArray[0].jsonObject
                .getValue("text").jsonPrimitive.content,
        ).jsonObject

        assertEquals(false, annotation.getValue("available").jsonPrimitive.boolean)

        val screenResponse = runSingleRequest(
            """{"jsonrpc":"2.0","id":"screen","method":"resources/read","params":{"uri":"pointpatch://screen/current"}}""",
            server = server,
        )
        val screen = parse(
            screenResponse.jsonObject
                .getValue("result").jsonObject
                .getValue("contents").jsonArray[0].jsonObject
                .getValue("text").jsonPrimitive.content,
        ).jsonObject

        assertEquals("com.default", screen.getValue("packageName").jsonPrimitive.content)
        assertTrue(bridge.calls.contains("inspectCurrentScreen:com.default"))
    }

    @Test
    fun currentScreenOnlyIncludesScreenshotResourceForDefaultPackageAnnotationWithScreenshot() {
        val bridge = FakeBridge()
        val server = server(bridge)

        val withoutAnnotation = runToolCall(
            server,
            "pointpatch_get_current_screen",
            """{"packageName":"com.first"}""",
        )
        assertFalse(withoutAnnotation.containsKey("screenshotResource"))

        runToolCall(
            server,
            "pointpatch_get_ui_feedback",
            """{"packageName":"com.second","timeoutMs":1500}""",
        )
        val otherPackageScreen = runToolCall(
            server,
            "pointpatch_get_current_screen",
            """{"packageName":"com.first"}""",
        )
        assertFalse(otherPackageScreen.containsKey("screenshotResource"))

        val samePackageScreen = runToolCall(
            server,
            "pointpatch_get_current_screen",
            """{"packageName":"com.second"}""",
        )
        assertFalse(samePackageScreen.containsKey("screenshotResource"))

        runToolCall(
            server,
            "pointpatch_get_ui_feedback",
            """{"timeoutMs":1500}""",
        )
        val defaultPackageScreen = runToolCall(
            server,
            "pointpatch_get_current_screen",
            """{}""",
        )
        assertEquals(
            "pointpatch://screenshot/latest/full.png",
            defaultPackageScreen.getValue("screenshotResource").jsonPrimitive.content,
        )
    }

    @Test
    fun currentScreenWithExplicitPackageDoesNotRequireDefaultPackageMetadata() {
        val response = runSingleRequest(
            """{"jsonrpc":"2.0","id":"tool","method":"tools/call","params":{"name":"pointpatch_get_current_screen","arguments":{"packageName":"com.explicit"}}}""",
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
            """{"jsonrpc":"2.0","id":"tool","method":"tools/call","params":{"name":"pointpatch_open_feedback_console","arguments":{"packageName":"com.explicit"}}}""",
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
    fun packageScopedCachesKeepDefaultAndEvictOlderOverridePackages() {
        val bridge = FakeBridge(defaultPackageName = "com.default")
        val server = server(bridge, defaultPackageName = "com.default")

        runToolCall(
            server,
            "pointpatch_get_ui_feedback",
            """{"timeoutMs":1500}""",
        )
        repeat(10) { index ->
            runToolCall(
                server,
                "pointpatch_get_ui_feedback",
                """{"packageName":"com.$index","timeoutMs":1500}""",
            )
        }

        val defaultScreen = runToolCall(
            server,
            "pointpatch_get_current_screen",
            """{}""",
        )
        val evictedOverrideScreen = runToolCall(
            server,
            "pointpatch_get_current_screen",
            """{"packageName":"com.0"}""",
        )
        val recentOverrideScreen = runToolCall(
            server,
            "pointpatch_get_current_screen",
            """{"packageName":"com.9"}""",
        )

        assertEquals("pointpatch://screenshot/latest/full.png", defaultScreen.getValue("screenshotResource").jsonPrimitive.content)
        assertFalse(evictedOverrideScreen.containsKey("screenshotResource"))
        assertFalse(recentOverrideScreen.containsKey("screenshotResource"))
    }

    @Test
    fun stdioCancellationNotificationIsProcessedWhileToolCallIsPending() = runBlocking {
        val bridge = BlockingFeedbackBridge()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val input = PipedInputStream()
        val inputWriter = PipedOutputStream(input).bufferedWriter(Charsets.UTF_8)
        val job = launch(Dispatchers.IO) {
            server(bridge).run(input = input, output = stdout, diagnostics = stderr)
        }

        try {
            inputWriter.write("""{"jsonrpc":"2.0","id":"feedback","method":"tools/call","params":{"name":"pointpatch_get_ui_feedback","arguments":{"timeoutMs":60000}}}""")
            inputWriter.newLine()
            inputWriter.flush()
            withTimeout(1_000) { bridge.started.await() }

            inputWriter.write("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"feedback","reason":"test cancellation"}}""")
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
        val bridge = BlockingFeedbackBridge()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val input = PipedInputStream()
        val inputWriter = PipedOutputStream(input).bufferedWriter(Charsets.UTF_8)
        val job = launch(Dispatchers.IO) {
            server(bridge).run(input = input, output = stdout, diagnostics = stderr)
        }

        inputWriter.write("""{"jsonrpc":"2.0","id":"feedback","method":"tools/call","params":{"name":"pointpatch_get_ui_feedback","arguments":{"timeoutMs":60000}}}""")
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
            inputWriter.write("""{"jsonrpc":"2.0","id":"screen","method":"resources/read","params":{"uri":"pointpatch://screen/current"}}""")
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

        inputWriter.write("""{"jsonrpc":"2.0","id":"screen","method":"resources/read","params":{"uri":"pointpatch://screen/current"}}""")
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

    private fun runSingleRequest(
        request: String,
        bridge: PointPatchBridge = FakeBridge(),
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
        bridge: PointPatchBridge = FakeBridge(),
        defaultPackageName: String? = null,
        projectRoot: File = File(".").canonicalFile,
        feedbackService: FeedbackSessionService = FeedbackSessionService(
            bridge = bridge,
            projectRoot = projectRoot.absolutePath,
            defaultPackageName = defaultPackageName,
        ),
    ): McpServer =
        McpServer(protocol = McpProtocol(tools = PointPatchTools(bridge, defaultPackageName, projectRoot, feedbackService)))

    private fun parse(value: String) = McpProtocol.json.parseToJsonElement(value)

    private fun feedbackService(bridge: PointPatchBridge, vararg ids: String): FeedbackSessionService =
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
        private val annotationEnabled: Boolean = true,
        private val defaultPackageName: String? = "io.github.pointpatch.sample",
        private val verificationMatches: Boolean = true,
    ) : PointPatchBridge {
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

        override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject {
            calls += "startFeedbackCapture:$packageName:$timeoutMillis"
            return buildJsonObject {
                put("submitted", true)
                put("timedOut", false)
                put("timeoutMillis", timeoutMillis)
                if (annotationEnabled) put("annotation", annotation(packageName))
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

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = JsonObject(emptyMap())

        private fun annotation(packageName: String): JsonObject = buildJsonObject {
            put("schemaVersion", "1.0")
            put("id", "annotation-1")
            put("createdAtEpochMillis", 1_700_000_000_000L)
            put("platform", "android-compose")
            put("app", buildJsonObject {
                put("packageName", packageName)
                put("debuggable", true)
            })
            put("activity", buildJsonObject { put("className", "io.github.pointpatch.sample.MainActivity") })
            put("tap", buildJsonObject {
                put("xInWindow", 24.0)
                put("yInWindow", 48.0)
            })
            put("selection", buildJsonObject {
                put("kind", "SEMANTICS_NODE")
                put("confidence", "HIGH")
                put("selectedUid", "node-1")
                put("source", "TAP_SELECT")
            })
            put("selectedNode", buildJsonObject {
                put("uid", "node-1")
                put("composeNodeId", 7)
                put("rootIndex", 0)
                put("treeKind", "MERGED")
                put("boundsInWindow", buildJsonObject {
                    put("left", 0.0)
                    put("top", 0.0)
                    put("right", 120.0)
                    put("bottom", 64.0)
                })
                put("text", JsonArray(listOf(JsonPrimitive("Pay now"))))
                put("role", "Button")
                put("enabled", true)
            })
            put("userComment", "Make this button more prominent")
            put("screenshot", buildJsonObject {
                put("desktopFullPath", "/tmp/pointpatch/annotation-1-full.png")
                put("desktopCropPath", "/tmp/pointpatch/annotation-1-crop.png")
            })
        }
    }

    private class BlockingFeedbackBridge : PointPatchBridge {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride?.takeIf { it.isNotBlank() } ?: "io.github.pointpatch.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject {
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

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = JsonObject(emptyMap())
    }

    private class BlockingResourceBridge : PointPatchBridge {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride?.takeIf { it.isNotBlank() } ?: "io.github.pointpatch.sample"

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

        override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject =
            JsonObject(emptyMap())

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
            JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = JsonObject(emptyMap())
    }
}
