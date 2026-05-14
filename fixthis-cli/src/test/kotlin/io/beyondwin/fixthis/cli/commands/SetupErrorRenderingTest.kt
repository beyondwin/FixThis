package io.beyondwin.fixthis.cli.commands

import io.beyondwin.fixthis.cli.DiagnosticContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import org.junit.Assert.assertTrue

class SetupErrorRenderingTest {

    @Before
    fun resetFlag() {
        DiagnosticContext.reset()
    }

    @After
    fun clearFlag() {
        DiagnosticContext.reset()
    }

    @Test
    fun rendersMalformedJsonCategoryForJsonDecodingException() {
        val configFile = File("/tmp/proj/.claude/settings.json")
        val cause = runCatching { Json.parseToJsonElement("{") }.exceptionOrNull()!!
        val rendered = renderMergeFailure("claude", configFile, cause)

        assertTrue(
            "Expected prefix line",
            rendered.startsWith("Could not merge claude MCP config at ${configFile.absolutePath}."),
        )
        assertTrue("Expected MALFORMED_JSON category", rendered.contains("Category: MALFORMED_JSON"))
        assertTrue("Expected caused by line", rendered.contains("caused by"))
        assertTrue("Expected Fix line", rendered.contains("Fix:"))
        assertTrue(
            "Expected verbose hint when DiagnosticContext.verbose is false",
            rendered.contains("Re-run with --verbose"),
        )
    }
}
