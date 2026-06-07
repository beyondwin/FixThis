package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.parse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Regression coverage for the A5 decoupling refactor: `install-agent --json` must keep its setup
 * warnings on stderr while leaving stdout as a pure JSON report.
 */
class InstallAgentWarningStreamTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installAgentJsonModeKeepsSetupWarningsOnStderrAndKeepsStdoutPureJson() {
        // Regression (A5): JSON mode must suppress human stdout echoes (so only the JSON report is
        // on stdout) but the setup warnings must STILL reach stderr. The legacy stdout-only discard
        // buffer never touched stderr, so the warnings survived in --json mode; the A5 refactor
        // wired emitWarning to a no-op in JSON mode, silently dropping them. This asserts the warning
        // lands on stderr AND never contaminates the JSON report on stdout.
        //
        // The Android-SDK warning is deterministic here: user.home is redirected to an empty temp
        // dir and ANDROID_HOME/ANDROID_SDK_ROOT are not set in CI, so AndroidSdkLocator.find()
        // returns null. The fixthis-mcp-executable warning is environment dependent (a Homebrew
        // install puts it on PATH), so we only assert it routes to stderr when it actually fires.
        val tempProject = temporaryFolder.newFolder("warn-json").also { setupFakeAndroidProject(it) }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        try {
            withUserHome(temporaryFolder.newFolder("home")) {
                InstallAgentCommand().parse(
                    arrayOf(
                        "--project-dir",
                        tempProject.absolutePath,
                        "--package",
                        "com.example.app",
                        "--target",
                        "claude",
                        "--json",
                        "--skip-gradle-plugin",
                    ),
                )
            }
        } catch (_: Throwable) {
            // tolerate non-zero exit; we assert on the captured streams.
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }

        val stdout = out.toString()
        val stderr = err.toString()

        // The deterministic SDK warning must reach stderr even in --json mode.
        assertTrue(
            "Android SDK warning must appear on stderr in --json mode, stderr was:\n$stderr",
            stderr.contains("Warning: Android SDK not found"),
        )
        // When the executable warning fires (no fixthis-mcp on PATH), it must also be on stderr.
        if (McpExecutableLocator.find() == null) {
            assertTrue(
                "fixthis-mcp executable warning must appear on stderr in --json mode, stderr was:\n$stderr",
                stderr.contains("Warning: fixthis-mcp executable not found"),
            )
        }

        // stdout must remain pure JSON: no warning text may leak onto it.
        assertFalse(
            "warnings must not pollute JSON stdout, stdout was:\n$stdout",
            stdout.contains("Warning:"),
        )
        assertFalse(
            "human next-steps trailer must not pollute JSON stdout, stdout was:\n$stdout",
            stdout.contains("Next for agents:"),
        )
        val rendered = stdout.lines().lastOrNull { it.trim().startsWith("{") }
        assertTrue("expected a JSON report line on stdout, got:\n$stdout", rendered != null)
        val obj = Json.parseToJsonElement(rendered!!).jsonObject
        assertEquals("1.0", obj.getValue("schemaVersion").jsonPrimitive.content)
    }

    private fun setupFakeAndroidProject(root: File) {
        File(root, "settings.gradle.kts").writeText("""include(":app")""")
        val app = File(root, "app").apply { mkdirs() }
        File(app, "build.gradle.kts").writeText(
            """android { defaultConfig { applicationId = "com.example.app" } }""",
        )
    }

    private fun <T> withUserHome(userHome: File, block: () -> T): T {
        val original = System.getProperty("user.home")
        System.setProperty("user.home", userHome.absolutePath)
        return try {
            block()
        } finally {
            if (original == null) {
                System.clearProperty("user.home")
            } else {
                System.setProperty("user.home", original)
            }
        }
    }
}
