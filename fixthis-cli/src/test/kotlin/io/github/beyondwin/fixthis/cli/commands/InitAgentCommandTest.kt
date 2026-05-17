package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import io.github.beyondwin.fixthis.cli.ExitCode
import io.github.beyondwin.fixthis.cli.buildRootCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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

class InitAgentCommandTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun initAgentWritesProjectScopedAgentFiles() {
        val projectRoot = androidProject("com.example.agent")

        withUserHome(temporaryFolder.newFolder("home")) {
            InitCommand().parse(
                listOf(
                    "--agent",
                    "--package",
                    "com.example.agent",
                    "--project-dir",
                    projectRoot.absolutePath,
                    "--target",
                    "claude",
                ),
            )
        }

        val setupGuide = projectRoot.resolve(".fixthis/agent-setup.md")
        assertTrue(setupGuide.isFile)
        val setupText = setupGuide.readText()
        assertTrue(setupText.contains("fixthis doctor --project-dir . --json"))
        assertTrue(setupText.contains("Restart Claude Code or Codex"))
        assertTrue(setupText.contains("fixthis_open_feedback_console"))
        assertTrue(setupText.contains("If doctor reports `NEEDS_INSTALL`"))
        assertFalse(setupText.contains("fixthis init --agent --project-dir ."))

        val mcpTemplate = projectRoot.resolve(".fixthis/mcp.json.template")
        assertTrue(mcpTemplate.isFile)
        val server = Json.parseToJsonElement(mcpTemplate.readText())
            .jsonObject
            .getValue("mcpServers")
            .jsonObject
            .getValue("fixthis")
            .jsonObject
        assertEquals("fixthis", server.getValue("command").jsonPrimitive.content)
        assertTrue(mcpTemplate.readText().contains("\"--project-dir\""))

        val agentManifest = projectRoot.resolve(".fixthis/agent-setup.json")
        assertTrue(agentManifest.isFile)
        val manifest = Json.parseToJsonElement(agentManifest.readText()).jsonObject
        assertEquals(
            "com.example.agent",
            manifest.getValue("state").jsonObject.getValue("packageName").jsonPrimitive.content,
        )
    }

    @Test
    fun installAgentAliasesAgentInit() {
        val projectRoot = androidProject("com.example.alias")

        withUserHome(temporaryFolder.newFolder("home")) {
            buildRootCommand().parse(
                listOf(
                    "install-agent",
                    "--package",
                    "com.example.alias",
                    "--project-dir",
                    projectRoot.absolutePath,
                    "--target",
                    "claude",
                ),
            )
        }

        assertTrue(projectRoot.resolve(".fixthis/agent-setup.md").isFile)
        assertTrue(projectRoot.resolve(".fixthis/agent-setup.json").isFile)
        assertTrue(projectRoot.resolve(".fixthis/mcp.json.template").isFile)
        assertTrue(projectRoot.resolve(".fixthis/project.json").isFile)
    }

    @Test
    fun installAgentAppliesGradlePluginToDetectedAppModule() {
        val projectRoot = androidProject("com.example.patch")
        val buildFile = projectRoot.resolve("app/build.gradle.kts")

        withUserHome(temporaryFolder.newFolder("home")) {
            buildRootCommand().parse(
                listOf(
                    "install-agent",
                    "--package",
                    "com.example.patch",
                    "--project-dir",
                    projectRoot.absolutePath,
                    "--target",
                    "claude",
                    "--plugin-version",
                    "0.2.0",
                ),
            )
        }

        val buildText = buildFile.readText()
        assertTrue(buildText.contains("""id("io.github.beyondwin.fixthis.compose") version "0.2.0""""))
    }

    @Test
    fun installAgentDryRunDoesNotPatchGradlePlugin() {
        val projectRoot = androidProject("com.example.dryrun")
        val buildFile = projectRoot.resolve("app/build.gradle.kts")
        val originalBuildText = buildFile.readText()

        withUserHome(temporaryFolder.newFolder("home")) {
            buildRootCommand().parse(
                listOf(
                    "install-agent",
                    "--package",
                    "com.example.dryrun",
                    "--project-dir",
                    projectRoot.absolutePath,
                    "--target",
                    "claude",
                    "--dry-run",
                    "--plugin-version",
                    "0.2.0",
                ),
            )
        }

        assertEquals(originalBuildText, buildFile.readText())
    }

    @Test
    fun initCanApplyGradlePluginWithoutAgentFiles() {
        val projectRoot = androidProject("com.example.initpatch")
        val buildFile = projectRoot.resolve("app/build.gradle.kts")

        withUserHome(temporaryFolder.newFolder("home")) {
            InitCommand().parse(
                listOf(
                    "--package",
                    "com.example.initpatch",
                    "--project-dir",
                    projectRoot.absolutePath,
                    "--target",
                    "claude",
                    "--apply-gradle-plugin",
                    "--plugin-version",
                    "0.2.0",
                ),
            )
        }

        assertTrue(buildFile.readText().contains("""id("io.github.beyondwin.fixthis.compose") version "0.2.0""""))
        assertTrue(!projectRoot.resolve(".fixthis/agent-setup.md").exists())
    }

    @Test
    fun installAgentSkipsCodexOnEmptyDirWithoutAllowGlobal() {
        val tempProject = temporaryFolder.newFolder("ft-noandroid")
        val out = ByteArrayOutputStream()
        val oldOut = System.out
        System.setOut(PrintStream(out))
        val statusCode: Int = try {
            withUserHome(temporaryFolder.newFolder("home")) {
                InstallAgentCommand().parse(
                    arrayOf(
                        "--project-dir",
                        tempProject.absolutePath,
                        "--package",
                        "com.example.app",
                        "--target",
                        "codex",
                        "--skip-gradle-plugin",
                    ),
                )
            }
            0
        } catch (e: CliktError) {
            e.statusCode
        } finally {
            System.setOut(oldOut)
        }
        val captured = out.toString()
        assertEquals(ExitCode.PARTIAL.value, statusCode)
        assertTrue(
            "expected no-android-context in output, got:\n$captured",
            captured.contains("no-android-context"),
        )
    }

    @Test
    fun installAgentAllowsCodexWithAllowGlobalFlag() {
        val tempProject = temporaryFolder.newFolder("ft-allow-global")
        val out = ByteArrayOutputStream()
        val oldOut = System.out
        System.setOut(PrintStream(out))
        try {
            withUserHome(temporaryFolder.newFolder("home")) {
                InstallAgentCommand().parse(
                    arrayOf(
                        "--project-dir", tempProject.absolutePath,
                        "--package", "com.example.app",
                        "--target", "codex",
                        "--skip-gradle-plugin",
                        "--allow-global",
                        "--dry-run",
                    ),
                )
            }
        } catch (_: Throwable) {
            // may exit non-zero downstream; we only care about the guard branch.
        } finally {
            System.setOut(oldOut)
        }
        val captured = out.toString()
        // The guard, when triggered, emits "Skipped codex: no-android-context. ..." (or a JSON
        // report entry with reason=no-android-context). The bare token now also appears as a
        // recovery key in agent-setup.json, so match the guard-specific phrasing instead.
        assertFalse(
            "guard should not skip when --allow-global is set",
            captured.contains("Skipped codex: no-android-context") ||
                captured.contains("\"reason\":\"no-android-context\"") ||
                captured.contains("\"reason\": \"no-android-context\""),
        )
    }

    @Test
    fun installAgentJsonModeEmitsSchemaAndAppliedTargets() {
        val tempProject = temporaryFolder.newFolder("real-android").also { setupFakeAndroidProject(it) }
        val out = ByteArrayOutputStream()
        val oldOut = System.out
        System.setOut(PrintStream(out))
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
        } catch (_: Throwable) { /* expected to be 0 here but tolerate non-zero */ } finally {
            System.setOut(oldOut)
        }
        val text = out.toString()
        val rendered = text.lines().lastOrNull { it.trim().startsWith("{") }
        assertTrue("expected at least one JSON line, got:\n$text", rendered != null)
        val obj = Json.parseToJsonElement(rendered!!).jsonObject
        assertEquals("1.0", obj.getValue("schemaVersion").jsonPrimitive.content)
        assertTrue(
            "expected claude in applied[]",
            obj.getValue("applied").jsonArray.any {
                it.jsonObject.getValue("target").jsonPrimitive.content == "claude"
            },
        )
    }

    @Test
    fun installAgentExitsPartialWhenSomeSkipped() {
        val tempProject = temporaryFolder.newFolder("ft-partial")
        val exitCode: Int = try {
            withUserHome(temporaryFolder.newFolder("home")) {
                InstallAgentCommand().parse(
                    arrayOf(
                        "--project-dir",
                        tempProject.absolutePath,
                        "--package",
                        "com.example.app",
                        "--target",
                        "all",
                        "--json",
                        "--skip-gradle-plugin",
                    ),
                )
            }
            0
        } catch (e: CliktError) {
            e.statusCode
        }
        assertEquals(ExitCode.PARTIAL.value, exitCode)
    }

    @Test
    fun installAgentExitsZeroWhenAllApplied() {
        val tempProject = temporaryFolder.newFolder("ft-all-applied")
        setupFakeAndroidProject(tempProject)
        val exitCode: Int = try {
            withUserHome(temporaryFolder.newFolder("home")) {
                InstallAgentCommand().parse(
                    arrayOf(
                        "--project-dir",
                        tempProject.absolutePath,
                        "--package",
                        "com.example.app",
                        "--target",
                        "claude",
                        "--skip-gradle-plugin",
                    ),
                )
            }
            0
        } catch (e: CliktError) {
            e.statusCode
        }
        assertEquals(ExitCode.OK.value, exitCode)
    }

    private fun setupFakeAndroidProject(root: File) {
        File(root, "settings.gradle.kts").writeText("""include(":app")""")
        val app = File(root, "app").apply { mkdirs() }
        File(app, "build.gradle.kts").writeText(
            """android { defaultConfig { applicationId = "com.example.app" } }""",
        )
    }

    private fun androidProject(applicationId: String): File {
        val projectRoot = temporaryFolder.newFolder("project").canonicalFile
        projectRoot.resolve("settings.gradle.kts").writeText("""include(":app")""")
        projectRoot.resolve("app").mkdirs()
        projectRoot.resolve("app/build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
            }

            android {
                defaultConfig {
                    applicationId = "$applicationId"
                }
            }
            """.trimIndent(),
        )
        return projectRoot
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
