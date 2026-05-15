package io.beyondwin.fixthis.cli.commands

import io.beyondwin.fixthis.cli.fixThisJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

internal object AgentSetupFiles {
    fun write(
        projectRoot: File,
        packageName: String,
        serverName: String,
        dryRun: Boolean,
        echo: (String) -> Unit,
    ) {
        val fixThisDirectory = projectRoot.resolve(".fixthis")
        val plans = listOf(
            WritePlan(fixThisDirectory.resolve("agent-setup.md"), agentSetupGuide(packageName)),
            WritePlan(
                fixThisDirectory.resolve("mcp.json.template"),
                fixThisJson.encodeToString(projectScopedMcpTemplate(serverName)) + "\n",
            ),
            WritePlan(
                fixThisDirectory.resolve("agent-setup.json"),
                fixThisJson.encodeToString(agentSetupManifest(packageName, serverName)) + "\n",
            ),
        )

        if (dryRun) {
            plans.forEach { plan ->
                echo("Would write ${plan.file.absolutePath}")
                echo(plan.content.trimEnd())
            }
            return
        }

        fixThisDirectory.mkdirs()
        plans.forEach { plan ->
            plan.file.writeText(plan.content)
            echo("Wrote ${plan.file.absolutePath}")
        }
    }

    private fun agentSetupGuide(packageName: String): String = """
        # FixThis Agent Setup

        This Android project is configured for FixThis package `$packageName`.

        Agent sequence from the project root:

        1. Run `fixthis install-agent --project-dir . --target all`.
        2. Run `./gradlew fixthisSetup`.
        3. Restart Claude Code or Codex so MCP config is reloaded.
        4. Run `fixthis doctor --project-dir . --json`.
        5. Use MCP tool `fixthis_open_feedback_console`.

        If the Gradle plugin is already applied, pass `--skip-gradle-plugin`
        to avoid editing the app module build file.
    """.trimIndent() + "\n"

    private fun projectScopedMcpTemplate(serverName: String) = buildJsonObject {
        put(
            "mcpServers",
            buildJsonObject {
                put(
                    serverName,
                    buildJsonObject {
                        put("command", "fixthis")
                        put(
                            "args",
                            buildJsonArray {
                                add(JsonPrimitive("mcp"))
                                add(JsonPrimitive("--project-dir"))
                                add(JsonPrimitive("."))
                            },
                        )
                    },
                )
            },
        )
    }

    private fun agentSetupManifest(packageName: String, serverName: String) = buildJsonObject {
        put("schemaVersion", "1.0")
        put("packageName", packageName)
        put("serverName", serverName)
        put(
            "commands",
            buildJsonArray {
                addCommand("installAgent", "fixthis install-agent --project-dir . --target all")
                addCommand("refreshGradleMetadata", "./gradlew fixthisSetup")
                addCommand("registerMcp", "fixthis init --agent --project-dir .")
                addCommand("verify", "fixthis doctor --project-dir . --json")
            },
        )
        put("openConsoleTool", "fixthis_open_feedback_console")
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addCommand(name: String, command: String) {
        add(
            buildJsonObject {
                put("name", name)
                put("command", command)
            },
        )
    }

    private data class WritePlan(val file: File, val content: String)
}
