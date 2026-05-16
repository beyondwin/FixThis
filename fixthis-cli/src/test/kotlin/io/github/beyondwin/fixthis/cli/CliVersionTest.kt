package io.github.beyondwin.fixthis.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliVersionTest {
    @Test
    fun textVersionIsSingleLine() {
        val rendered = renderCliVersion(json = false, cliVersion = "0.2.3", bridgeProtocolVersion = "1.3")
        assertEquals("fixthis 0.2.3 (bridge protocol v1.3)", rendered.trim())
        assertTrue(rendered.endsWith("\n"))
    }

    @Test
    fun jsonVersionContainsCliAndBridgeFields() {
        val rendered = renderCliVersion(json = true, cliVersion = "0.2.3", bridgeProtocolVersion = "1.3")
        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals("0.2.3", obj.getValue("cliVersion").jsonPrimitive.content)
        assertEquals("1.3", obj.getValue("bridgeProtocolVersion").jsonPrimitive.content)
    }
}
