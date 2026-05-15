package io.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.parse
import io.beyondwin.fixthis.cli.buildRootCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
        assertTrue(setupText.contains("fixthis init --agent --project-dir ."))
        assertTrue(setupText.contains("fixthis doctor --project-dir ."))
        assertTrue(setupText.contains("fixthis_open_feedback_console"))

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
        assertEquals("com.example.agent", manifest.getValue("packageName").jsonPrimitive.content)
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
