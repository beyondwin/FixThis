package io.github.beyondwin.fixthis.mcp.console

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackConsoleAssetsTest {
    private val tempDir: File = Files.createTempDirectory("assets-cfg").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private class CountingShaResolver(private val value: String?) : () -> String? {
        var calls: Int = 0
            private set

        override fun invoke(): String? {
            calls += 1
            return value
        }
    }

    private class ThrowingShaResolver(private val error: Throwable) : () -> String? {
        override fun invoke(): String? = throw error
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

    @Test
    fun bundledClasspathResourcesAreCachedAfterFirstRead() {
        var reads = 0
        val assets = FeedbackConsoleAssets(
            shaResolver = StaticShaResolver(value = "abcdef1"),
            clock = { 1000L },
            classpathResourceLoader = {
                reads += 1
                if (reads == 1) {
                    "cached-resource".toByteArray()
                } else {
                    throw IOException("jar was replaced while the console was running")
                }
            },
        )

        assertEquals("cached-resource", assets.resource("index.html").toString(Charsets.UTF_8))
        assertEquals("cached-resource", assets.resource("index.html").toString(Charsets.UTF_8))
        assertEquals(1, reads)
    }

    @Test
    fun packagedModeDoesNotSetDevReloadFlag() {
        val html = FeedbackConsoleAssets.html(consoleAssetsDir = null)
        assertTrue(html.contains("window.FixThisConsoleConfig"))
        assertFalse(html.contains("devReloadEnabled: true"))
    }

    @Test
    fun dirModeSetsDevReloadEnabledAndBuildHash() {
        File(tempDir, "console-build-meta.json")
            .writeText("""{"buildEpochMs":0,"gitSha":"abc1234"}""" + "\n")
        File(tempDir, "index.html").writeText(
            "<html><head><!-- FIXTHIS_STYLES --></head><body><!-- FIXTHIS_SCRIPT --></body></html>",
        )
        File(tempDir, "styles.css").writeText("")
        File(tempDir, "app.js").writeText("")
        val html = FeedbackConsoleAssets.html(tempDir)
        assertTrue(html.contains("devReloadEnabled: true"), "dir mode must set devReloadEnabled")
        assertTrue(html.contains("\"abc1234\""), "buildHash must be inlined from console-build-meta.json")
    }
}
