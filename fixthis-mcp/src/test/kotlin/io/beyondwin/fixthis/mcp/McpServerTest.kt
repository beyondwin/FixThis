package io.beyondwin.fixthis.mcp

import io.beyondwin.fixthis.cli.BridgeClient
import io.beyondwin.fixthis.mcp.tools.CliFixThisBridge
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerTest {
    @Test
    fun consoleStartupResultMarksToolErrors() {
        val result = consoleStartupResult(
            toolResult(
                isError = true,
                content = listOf(textContent("Package name could not be resolved")),
            ),
        )

        assertTrue(result.isError)
        assertEquals("Package name could not be resolved", result.text)
    }

    @Test
    fun consoleStartupResultReturnsSuccessText() {
        val result = consoleStartupResult(
            toolResult(
                content = listOf(textContent("""{"consoleUrl":"http://127.0.0.1:1234/"}""")),
            ),
        )

        assertFalse(result.isError)
        assertEquals("""{"consoleUrl":"http://127.0.0.1:1234/"}""", result.text)
    }

    @Test
    fun stdioToolsUseConfiguredProjectDir() = runBlocking {
        val projectDir = File("/tmp/fixthis-project").canonicalFile
        val options = McpOptions(
            packageName = "io.beyondwin.fixthis.sample",
            projectDir = projectDir,
            consoleMode = false,
        )
        val bridge = CliFixThisBridge(BridgeClient(projectRoot = projectDir))
        val tools = fixThisToolsForOptions(options, bridge)

        val result = tools.call("fixthis_open_feedback_console", JsonObject(emptyMap()))
        val text = result.getValue("content")
            .jsonArray
            .first()
            .jsonObject
            .getValue("text")
            .jsonPrimitive
            .content
        val payload = McpProtocol.json.parseToJsonElement(text).jsonObject

        assertEquals(projectDir.absolutePath, payload.getValue("projectRoot").jsonPrimitive.content)
    }
}
