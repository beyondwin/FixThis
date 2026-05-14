package io.beyondwin.fixthis.cli.commands

import io.beyondwin.fixthis.cli.DiagnosticContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import org.junit.Assert.assertEquals
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

    @Test
    fun rendersMalformedMcpServersShapeCategoryWhenIllegalArgumentMentionsMcpServers() {
        val configFile = File("/tmp/proj/.claude/settings.json")
        val cause = IllegalArgumentException(
            "\"mcpServers\" in existing .claude/settings.json is not a JSON object (found JsonArray). " +
                "Fix the file manually before running fixthis setup.",
        )
        val rendered = renderMergeFailure("claude", configFile, cause)
        assertTrue(
            "Expected MALFORMED_MCPSERVERS_SHAPE category, got: $rendered",
            rendered.contains("Category: MALFORMED_MCPSERVERS_SHAPE"),
        )
        assertTrue(rendered.contains("replace it with `{}`"))
    }

    @Test
    fun rendersMalformedTomlCategoryForCodexFailure() {
        val configFile = File("/tmp/home/.codex/config.toml")
        val cause = IllegalStateException("bad TOML at line 4")
        val rendered = renderMergeFailure("codex", configFile, cause)
        assertTrue(rendered.contains("Category: MALFORMED_TOML"))
        assertTrue(rendered.contains("[mcp_servers.fixthis]"))
    }

    @Test
    fun rendersMalformedTomlCategoryForCodexIllegalArgument() {
        // CodexConfigWriter raises IllegalArgumentException from key normalization;
        // it must classify as MALFORMED_TOML, not UNKNOWN.
        val configFile = File("/tmp/home/.codex/config.toml")
        val cause = IllegalArgumentException("invalid key name \"!!\"")
        val rendered = renderMergeFailure("codex", configFile, cause)
        assertTrue(
            "Expected MALFORMED_TOML for IllegalArgumentException from CodexConfigWriter, got: $rendered",
            rendered.contains("Category: MALFORMED_TOML"),
        )
    }

    @Test
    fun rendersFilesystemErrorCategoryForIoException() {
        val configFile = File("/tmp/proj/.claude/settings.json")
        val cause = java.io.IOException("Permission denied")
        val rendered = renderMergeFailure("claude", configFile, cause)
        assertTrue(rendered.contains("Category: FILESYSTEM_ERROR"))
        assertTrue(rendered.contains("chmod"))
    }

    @Test
    fun rendersUnknownCategoryForOpaqueException() {
        val configFile = File("/tmp/proj/.claude/settings.json")
        val cause = RuntimeException("something opaque")
        val rendered = renderMergeFailure("claude", configFile, cause)
        assertTrue(rendered.contains("Category: UNKNOWN"))
        assertTrue(rendered.contains("--verbose"))
    }

    @Test
    fun walksCauseChainAndIndentsByDepth() {
        val deepest = RuntimeException("c")
        val middle = RuntimeException("b", deepest)
        val top = RuntimeException("a", middle)
        val lines = renderCauseChain(top)
        assertEquals(3, lines.size)
        assertTrue("first line indent 0", lines[0].startsWith("caused by"))
        assertTrue("second line indent 2", lines[1].startsWith("  caused by"))
        assertTrue("third line indent 4", lines[2].startsWith("    caused by"))
        assertTrue(lines[0].contains("a"))
        assertTrue(lines[2].contains("c"))
    }

    @Test
    fun truncatesPathologicalSelfReferenceCause() {
        // Build a Throwable whose `cause` returns itself. Java's Throwable.initCause
        // forbids self-cause, so subclass and override the `cause` property.
        class SelfReferencing : RuntimeException("self") {
            override val cause: Throwable get() = this
        }
        val self = SelfReferencing()
        val lines = renderCauseChain(self)
        assertTrue(
            "Expected truncation marker, got: $lines",
            lines.last().contains("self-reference") || lines.last().contains("cycle detected"),
        )
    }

    @Test
    fun verboseHintOmittedWhenDiagnosticContextVerboseTrue() {
        val configFile = File("/tmp/proj/.claude/settings.json")
        val cause = RuntimeException("opaque")
        DiagnosticContext.verbose = true
        val rendered = renderMergeFailure("claude", configFile, cause)
        assertTrue(
            "Did not expect --verbose hint when verbose is true, got: $rendered",
            !rendered.contains("Re-run with --verbose"),
        )
    }
}
