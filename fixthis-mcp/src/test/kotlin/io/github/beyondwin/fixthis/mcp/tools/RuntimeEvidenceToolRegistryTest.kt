package io.github.beyondwin.fixthis.mcp.tools

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeEvidenceToolRegistryTest {
    @Test
    fun toolRegistryExposesRuntimeEvidenceTool() {
        val tools = McpToolRegistry.listTools()
        val names = tools.jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

        assertTrue("fixthis_capture_runtime_evidence" in names)
    }

    @Test
    fun runtimeEvidenceToolSchemaPinsRequiredFieldsAndEvidenceTypes() {
        val tool = McpToolRegistry.listTools().jsonArray
            .first { it.jsonObject.getValue("name").jsonPrimitive.content == "fixthis_capture_runtime_evidence" }
            .jsonObject
        val schema = tool.getValue("inputSchema").jsonObject
        val properties = schema.getValue("properties").jsonObject
        val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        val typeEnum = properties.getValue("type").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }

        assertEquals(false, schema.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(listOf("itemId", "type", "summary"), required)
        assertEquals(listOf("logcat_window", "frame_summary", "memory_summary", "trace_artifact"), typeEnum)
        assertTrue("artifactPath" in properties.keys)
    }
}
