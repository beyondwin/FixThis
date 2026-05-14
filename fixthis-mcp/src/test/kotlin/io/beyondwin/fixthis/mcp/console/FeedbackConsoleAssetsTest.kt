package io.beyondwin.fixthis.mcp.console

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FeedbackConsoleAssetsTest {
    private class CountingShaResolver(private val value: String?) : () -> String? {
        var calls: Int = 0
            private set

        override fun invoke(): String? {
            calls += 1
            return value
        }
    }

    private class ThrowingShaResolver(private val error: Throwable) : () -> String? {
        override fun invoke(): String? {
            throw error
        }
    }

    private class StaticShaResolver(private val value: String?) : () -> String? {
        override fun invoke(): String? = value
    }

    @Test
    fun reproducibleMetaResolvesRuntimeShaOnlyOnce() {
        val resolver = CountingShaResolver(value = "abcdef1")
        val assets = FeedbackConsoleAssets(shaResolver = resolver, clock = { 1000L })
        repeat(5) { assets.buildIndexHtml(consoleAssetsDir = null, consoleToken = "") }
        assertEquals(1, resolver.calls)
    }

    @Test
    fun reproducibleMetaFallsBackWhenGitMissing() {
        val errors = StringBuilder()
        val assets = FeedbackConsoleAssets(
            shaResolver = ThrowingShaResolver(IOException("git not found")),
            clock = { 1000L },
            errSink = { errors.appendLine(it) },
        )
        val html = assets.buildIndexHtml(consoleAssetsDir = null, consoleToken = "")
        assertContains(html, "\"gitSha\":\"unknown\"")
        assertContains(errors.toString(), "git not found")
    }

    @Test
    fun shaResolverRejectsNonHexOutput() {
        val resolver = StaticShaResolver(value = "fatal: not a git repo")
        val assets = FeedbackConsoleAssets(shaResolver = resolver, clock = { 1000L })
        val html = assets.buildIndexHtml(consoleAssetsDir = null, consoleToken = "")
        assertContains(html, "\"gitSha\":\"unknown\"")
    }
}
