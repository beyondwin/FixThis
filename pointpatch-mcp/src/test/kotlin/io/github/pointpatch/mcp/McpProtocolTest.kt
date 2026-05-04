package io.github.pointpatch.mcp

import io.github.pointpatch.mcp.tools.PointPatchBridge
import io.github.pointpatch.mcp.tools.PointPatchTools
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
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

        assertTrue(content.any { it.contains("\"id\": \"annotation-1\"") })
        assertTrue(content.any { it.contains("# PointPatch Compose Feedback") })
        assertTrue(response.jsonObject.getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean.not())
    }

    private fun runSingleRequest(request: String, bridge: PointPatchBridge = FakeBridge()): kotlinx.serialization.json.JsonElement {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        runBlocking {
            server(bridge).run(
                input = ByteArrayInputStream((request + "\n").toByteArray()),
                output = stdout,
                diagnostics = stderr,
            )
        }
        return parse(stdout.toString().trim().lines().single())
    }

    private fun server(bridge: PointPatchBridge = FakeBridge()): McpServer =
        McpServer(protocol = McpProtocol(tools = PointPatchTools(bridge)))

    private fun parse(value: String) = McpProtocol.json.parseToJsonElement(value)

    private class FakeBridge : PointPatchBridge {
        val calls = mutableListOf<String>()

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride?.takeIf { it.isNotBlank() } ?: "io.github.pointpatch.sample"

        override suspend fun status(packageName: String): JsonObject {
            calls += "status:$packageName"
            return buildJsonObject {
                put("activity", "io.github.pointpatch.sample.MainActivity")
                put("rootsCount", 1)
                put("sidekickVersion", "1.0")
                put("bridgeProtocolVersion", "1.0")
                put("sourceIndexAvailable", true)
            }
        }

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject {
            calls += "inspectCurrentScreen:$packageName"
            return buildJsonObject {
                put("activity", "io.github.pointpatch.sample.MainActivity")
                put("roots", JsonArray(emptyList()))
            }
        }

        override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject {
            calls += "startFeedbackCapture:$packageName:$timeoutMillis"
            return buildJsonObject {
                put("submitted", true)
                put("timedOut", false)
                put("timeoutMillis", timeoutMillis)
                put("annotation", annotation(packageName))
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
}
