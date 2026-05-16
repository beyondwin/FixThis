package io.github.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DryRunDiffTest {

    @Test
    fun jsonDiffShowsOnlyAddedEntries() {
        val before = """{"mcpServers":{"existing":{"command":"x"}}}"""
        val after = """{"mcpServers":{"existing":{"command":"x"},"fixthis":{"command":"y"}}}"""
        val diff = DryRunDiff.render(before = before, after = after, format = DryRunDiff.Format.JSON)
        assertTrue("expected '+ fixthis' entry, got:\n$diff", diff.contains("+ ") && diff.contains("\"fixthis\""))
        assertFalse("dry-run leaked existing server", diff.contains("\"existing\":{\"command\":\"x\"}"))
    }

    @Test
    fun tomlDiffShowsOnlyAddedSection() {
        val before = """
            [other]
            key = "value"
        """.trimIndent()
        val after = before + "\n" + """
            [mcp_servers.fixthis]
            command = "x"
        """.trimIndent()
        val diff = DryRunDiff.render(before = before, after = after, format = DryRunDiff.Format.TOML)
        assertTrue(diff.contains("+ [mcp_servers.fixthis]"))
        assertFalse(diff.contains("[other]"))
    }

    @Test
    fun outputExceedingByteBudgetIsElidedWithFooter() {
        val before = ""
        // Use a sequence of repeated lines so the TOML line-set diff actually emits content >4KiB.
        // (We test byte-budget on the TOML path since JSON parsing rejects raw "x" repeats.)
        val after = (1..400).joinToString("\n") { "[section_$it]" }  // ~ several KiB, > 4 KiB after "+ " prefix
        val diff = DryRunDiff.render(
            before = before, after = after, format = DryRunDiff.Format.TOML, byteBudget = 4096,
        )
        val sizeBytes = diff.toByteArray(Charsets.UTF_8).size
        assertTrue("expected elision footer, got bytes=$sizeBytes",
            diff.contains("elided") && sizeBytes <= 4096 + 200)
    }

    @Test
    fun privacyMarkerInBeforeNeverLeaksToOutput() {
        val marker = "SECRET-MARKER-XYZ"
        val before = """{"mcpServers":{"private":{"token":"$marker"}}}"""
        val after = """{"mcpServers":{"private":{"token":"$marker"},"fixthis":{"command":"y"}}}"""
        val diff = DryRunDiff.render(before = before, after = after, format = DryRunDiff.Format.JSON)
        assertFalse("marker leaked to dry-run output:\n$diff", diff.contains(marker))
    }

    @Test
    fun privacyMarkerNeverLeaksEvenOnMalformedJson() {
        val marker = "SECRET-MARKER-MALFORMED"
        // Malformed: missing trailing }. This will fail to parse.
        val before = """{"mcpServers":{"private":{"token":"$marker"}}"""
        val after = """{"mcpServers":{"private":{"token":"$marker"},"fixthis":{"command":"y"}}}"""
        val diff = DryRunDiff.render(before = before, after = after, format = DryRunDiff.Format.JSON)
        assertFalse("marker leaked via parse-error fallback:\n$diff", diff.contains(marker))
    }
}
