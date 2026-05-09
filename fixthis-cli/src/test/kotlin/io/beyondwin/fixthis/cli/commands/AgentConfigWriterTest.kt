package io.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigWriterTest {
    private val entry = McpConfigEntry(
        serverName = "fixthis",
        command = "/repo/fixthis-cli/build/install/fixthis/bin/fixthis",
        args = listOf("mcp", "--package", "io.beyondwin.fixthis.sample", "--project-dir", "/repo"),
        env = mapOf("ANDROID_HOME" to "/Users/kws/Library/Android/sdk"),
    )

    @Test
    fun codexMergeReplacesOnlyFixThisSection() {
        val current = """
            [mcp_servers.playwright]
            command = "npx"
            args = ["-y", "@playwright/mcp"]

            [mcp_servers.fixthis]
            command = "old"
        """.trimIndent()

        val merged = CodexConfigWriter().merge(current, entry)

        assertTrue(merged.contains("[mcp_servers.playwright]"))
        assertTrue(merged.contains("[mcp_servers.fixthis]"))
        assertTrue(merged.contains("[mcp_servers.fixthis.env]"))
        assertTrue(merged.contains("ANDROID_HOME = \"/Users/kws/Library/Android/sdk\""))
        assertFalse(merged.contains("command = \"old\""))
    }

    @Test
    fun claudeMergePreservesOtherServers() {
        val current = """{"mcpServers":{"playwright":{"command":"npx","args":["-y","@playwright/mcp"]}}}"""

        val merged = ClaudeConfigWriter().merge(current, entry)

        assertTrue(merged.contains("\"playwright\""))
        assertTrue(merged.contains("\"fixthis\""))
        assertTrue(merged.contains("\"ANDROID_HOME\""))
    }

    @Test
    fun codexMergeRemovesStaleFixThisEnv() {
        val current = """
            [mcp_servers.fixthis]
            command = "old"

            [mcp_servers.fixthis.env]
            ANDROID_HOME = "/old/sdk"

            [mcp_servers.playwright]
            command = "npx"
        """.trimIndent()

        val merged = CodexConfigWriter().merge(current, entry)

        assertFalse(merged.contains("/old/sdk"))
        assertTrue(merged.contains("[mcp_servers.playwright]"))
        assertTrue(merged.contains("ANDROID_HOME = \"/Users/kws/Library/Android/sdk\""))
    }

    @Test
    fun codexMergeRemovesSeparatedStaleFixThisEnv() {
        val current = """
            [mcp_servers.fixthis]
            command = "old"

            [mcp_servers.playwright]
            command = "npx"

            [mcp_servers.fixthis.env]
            ANDROID_HOME = "/old/sdk"
        """.trimIndent()

        val merged = CodexConfigWriter().merge(current, entry)

        assertFalse(merged.contains("/old/sdk"))
        assertTrue(merged.contains("[mcp_servers.playwright]"))
        assertTrue(merged.contains("ANDROID_HOME = \"/Users/kws/Library/Android/sdk\""))
    }

    @Test
    fun codexMergeReplacesCommentedFixThisHeader() {
        val current = """
            [mcp_servers.fixthis] # FixThis server
            command = "old"

            [mcp_servers.playwright]
            command = "npx"
        """.trimIndent()

        val merged = CodexConfigWriter().merge(current, entry)

        assertTrue(merged.contains("[mcp_servers.playwright]"))
        assertTrue(merged.contains("[mcp_servers.fixthis]"))
        assertFalse(merged.contains("command = \"old\""))
        assertFalse(merged.contains("[mcp_servers.fixthis] # FixThis server"))
    }

    @Test
    fun codexMergeReplacesQuotedFixThisHeader() {
        val current = """
            [mcp_servers."fixthis"]
            command = "old"

            [mcp_servers.playwright]
            command = "npx"
        """.trimIndent()

        val merged = CodexConfigWriter().merge(current, entry)

        assertTrue(merged.contains("[mcp_servers.playwright]"))
        assertTrue(merged.contains("[mcp_servers.fixthis]"))
        assertFalse(merged.contains("command = \"old\""))
        assertFalse(merged.contains("[mcp_servers.\"fixthis\"]"))
    }

    @Test
    fun codexMergeReplacesSingleQuotedFixThisHeader() {
        val current = """
            [mcp_servers.'fixthis']
            command = "old"

            [mcp_servers.playwright]
            command = "npx"
        """.trimIndent()

        val merged = CodexConfigWriter().merge(current, entry)

        assertTrue(merged.contains("[mcp_servers.playwright]"))
        assertTrue(merged.contains("[mcp_servers.fixthis]"))
        assertFalse(merged.contains("command = \"old\""))
        assertFalse(merged.contains("[mcp_servers.'fixthis']"))
    }

    @Test
    fun codexMergeRemovesSingleQuotedStaleFixThisEnvAndPreservesOtherEnv() {
        val current = """
            [mcp_servers.'fixthis']
            command = "old"

            [mcp_servers.playwright]
            command = "npx"

            [mcp_servers.'fixthis'.env]
            ANDROID_HOME = "/old/sdk"

            [mcp_servers.'other'.env]
            TOKEN = "keep"
        """.trimIndent()

        val merged = CodexConfigWriter().merge(current, entry)

        assertFalse(merged.contains("/old/sdk"))
        assertTrue(merged.contains("[mcp_servers.playwright]"))
        assertTrue(merged.contains("[mcp_servers.'other'.env]"))
        assertTrue(merged.contains("TOKEN = \"keep\""))
        assertTrue(merged.contains("ANDROID_HOME = \"/Users/kws/Library/Android/sdk\""))
    }

    @Test
    fun claudeMergeThrowsWhenMcpServersIsArray() {
        val current = """{"mcpServers":[]}"""
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ClaudeConfigWriter().merge(current, entry)
        }
        assertTrue(
            "Expected message to mention mcpServers",
            ex.message!!.contains("\"mcpServers\""),
        )
        assertTrue(
            "Expected message to say not a JSON object",
            ex.message!!.contains("not a JSON object"),
        )
        assertTrue(
            "Expected message to say fix manually",
            ex.message!!.contains("Fix the file manually"),
        )
    }

    @Test
    fun claudeMergeThrowsWhenMcpServersIsString() {
        val current = """{"mcpServers":"wrong"}"""
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ClaudeConfigWriter().merge(current, entry)
        }
        assertTrue(ex.message!!.contains("not a JSON object"))
    }

    @Test
    fun claudeMergeAcceptsAbsentMcpServers() {
        val current = """{"otherKey":"value"}"""
        val merged = ClaudeConfigWriter().merge(current, entry)
        assertTrue("Expected fixthis server to appear", merged.contains("\"fixthis\""))
        assertTrue("Expected other keys preserved", merged.contains("\"otherKey\""))
    }

    @Test
    fun claudeWriterScopeIsProjectLocal() {
        assertEquals("project-local", ClaudeConfigWriter().scope)
    }

    @Test
    fun codexWriterScopeIsGlobal() {
        assertEquals("global", CodexConfigWriter().scope)
    }
}
