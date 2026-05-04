package io.github.pointpatch.mcp

import io.github.pointpatch.mcp.tools.PointPatchBridge
import io.github.pointpatch.mcp.tools.PointPatchTools
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
    fun toolsListIncludesFourPointPatchTools() {
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
            ),
            tools,
        )
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
    fun currentScreenOnlyIncludesScreenshotResourceForSamePackageAnnotationWithScreenshot() {
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
        assertEquals(
            "pointpatch://screenshot/latest/full.png",
            samePackageScreen.getValue("screenshotResource").jsonPrimitive.content,
        )
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
        assertEquals("pointpatch://screenshot/latest/full.png", recentOverrideScreen.getValue("screenshotResource").jsonPrimitive.content)
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
        val response = runSingleRequest(
            """{"jsonrpc":"2.0","id":"tool","method":"tools/call","params":{"name":"$name","arguments":$argumentsJson}}""",
            server = server,
        )
        val content = response.jsonObject
            .getValue("result").jsonObject
            .getValue("content").jsonArray[0].jsonObject
            .getValue("text").jsonPrimitive.content
        return parse(content).jsonObject
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

    private fun server(bridge: PointPatchBridge = FakeBridge(), defaultPackageName: String? = null): McpServer =
        McpServer(protocol = McpProtocol(tools = PointPatchTools(bridge, defaultPackageName)))

    private fun parse(value: String) = McpProtocol.json.parseToJsonElement(value)

    private class FakeBridge(
        private val annotationEnabled: Boolean = true,
        private val defaultPackageName: String = "io.github.pointpatch.sample",
    ) : PointPatchBridge {
        val calls = mutableListOf<String>()

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride?.takeIf { it.isNotBlank() } ?: defaultPackageName

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
                put("verified", true)
                put("expectedText", expectedText)
                put("matchedText", expectedText)
            }
        }

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
    }
}
