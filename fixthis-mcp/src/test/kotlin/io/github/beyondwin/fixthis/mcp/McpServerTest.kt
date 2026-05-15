package io.github.beyondwin.fixthis.mcp

import io.github.beyondwin.fixthis.cli.BridgeClient
import io.github.beyondwin.fixthis.mcp.tools.CliFixThisBridge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun parsesConsoleAssetsDirectoryOption() {
        val options = McpOptions.parse(
            arrayOf(
                "--console",
                "--package",
                "io.github.beyondwin.fixthis.sample",
                "--project-dir",
                "/repo",
                "--console-assets-dir",
                "/repo/fixthis-mcp/src/main/resources/console",
            ),
        )

        assertTrue(options.consoleMode)
        assertEquals("io.github.beyondwin.fixthis.sample", options.packageName)
        assertEquals(0, options.consolePort)
        assertEquals(
            File("/repo/fixthis-mcp/src/main/resources/console").canonicalFile,
            options.consoleAssetsDir,
        )
    }

    @Test
    fun parsesConsolePortOption() {
        val options = McpOptions.parse(
            arrayOf(
                "--console",
                "--console-port",
                "60006",
            ),
        )

        assertTrue(options.consoleMode)
        assertEquals(60006, options.consolePort)
    }

    @Test
    fun stdioToolsUseConfiguredProjectDir() = runBlocking {
        val projectDir = File("/tmp/fixthis-project").canonicalFile
        val options = McpOptions(
            packageName = "io.github.beyondwin.fixthis.sample",
            projectDir = projectDir,
            consoleMode = false,
            consoleAssetsDir = null,
            consolePort = 0,
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
