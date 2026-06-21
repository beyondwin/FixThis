package io.github.beyondwin.fixthis.mcp.tools

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeEvidenceToolRegistryTest {
    @Test
    fun toolRegistryExposesRuntimeEvidenceTool() {
        val tools = McpToolRegistry.listTools()
        val names = tools.jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

        assertTrue("fixthis_capture_runtime_evidence" in names)
    }
}
