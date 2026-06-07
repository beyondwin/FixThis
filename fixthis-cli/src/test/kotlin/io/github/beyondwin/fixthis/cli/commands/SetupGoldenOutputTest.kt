package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Characterization (golden-output) safety net for the upcoming Track A refactor (A2–A6).
 *
 * These tests pin the observable SHAPE of:
 *  - `install-agent --json` stdout (the structured JSON report), and
 *  - `setup --write --dry-run` echo lines (the `Target:` / `Path:` diff headers).
 *
 * They assert field PRESENCE and shape only (not exact byte values) so they stay green
 * across the decoupling refactor while still catching accidental contract breaks.
 */
class SetupGoldenOutputTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `install-agent json dry-run emits stable report shape`() {
        val tmp = createAndroidProjectRoot()
        val out = captureStdout {
            withUserHome(temporaryFolder.newFolder("home")) {
                try {
                    InstallAgentCommand().parse(
                        listOf(
                            "--json",
                            "--dry-run",
                            "--project-dir",
                            tmp.absolutePath,
                            "--package",
                            "com.example.app",
                            "--target",
                            "claude",
                            "--skip-gradle-plugin",
                        ),
                    )
                } catch (_: CliktError) {
                    // Exit code may be non-zero downstream; we only characterize the JSON report.
                }
            }
        }

        val rendered = out.lines().lastOrNull { it.trim().startsWith("{") }
        assertTrue("expected a JSON report line on stdout, got:\n$out", rendered != null)
        val json = Json.parseToJsonElement(rendered!!).jsonObject

        // Top-level keys required by the behavior-preservation contract (Track A).
        listOf("schemaVersion", "ok", "applied", "skipped", "errors", "next", "readiness", "restartRequired").forEach { key ->
            assertTrue("expected top-level field `$key`, got: ${json.keys}", key in json)
        }

        // Shape checks.
        assertTrue("`applied` must be an array", json.getValue("applied") is JsonArray)
        assertTrue("`skipped` must be an array", json.getValue("skipped") is JsonArray)
        assertTrue("`errors` must be an array", json.getValue("errors") is JsonArray)
        assertTrue("`next` must be an array", json.getValue("next") is JsonArray)
        assertTrue("`ok` must be a primitive", json.getValue("ok") is JsonPrimitive)
        assertTrue("`restartRequired` must be a primitive", json.getValue("restartRequired") is JsonPrimitive)
    }

    @Test
    fun `setup write dry-run echoes target and path prefixes`() {
        val tmp = createAndroidProjectRoot()
        val out = captureStdout {
            withUserHome(temporaryFolder.newFolder("home")) {
                SetupCommand().parse(
                    listOf(
                        "--write",
                        "--dry-run",
                        "--project-dir",
                        tmp.absolutePath,
                        "--package",
                        "com.example.app",
                        "--target",
                        "claude",
                    ),
                )
            }
        }

        val lines = out.lines()
        assertTrue(
            "expected a `Target:` echo line, got:\n$out",
            lines.any { it.startsWith("Target:") },
        )
        assertTrue(
            "expected a `Path:` echo line, got:\n$out",
            lines.any { it.startsWith("Path:") },
        )
    }

    private fun createAndroidProjectRoot(): File {
        val root = temporaryFolder.newFolder("project").canonicalFile
        File(root, "settings.gradle.kts").writeText("""include(":app")""")
        val app = File(root, "app").apply { mkdirs() }
        File(app, "build.gradle.kts").writeText(
            """android { defaultConfig { applicationId = "com.example.app" } }""",
        )
        return root
    }

    private fun captureStdout(block: () -> Unit): String {
        val buffer = ByteArrayOutputStream()
        val previous = System.out
        System.setOut(PrintStream(buffer))
        return try {
            block()
            buffer.toString()
        } finally {
            System.setOut(previous)
        }
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
