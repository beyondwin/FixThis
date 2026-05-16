package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.fixThisJson
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
                fixThisJson.encodeToString(agentSetupManifest(packageName, projectRoot)) + "\n",
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

        1. Run `./gradlew fixthisSetup`.
        2. Run `fixthis init --agent --project-dir .`.
        3. Restart Claude Code or Codex so MCP config is reloaded.
        4. Run `fixthis doctor --project-dir . --json`.
        5. Use MCP tool `fixthis_open_feedback_console`.

        If `fixthisSetup` is missing, apply Gradle plugin
        `io.github.beyondwin.fixthis.compose` to the Android app module or rerun
        `fixthis install-agent --project-dir . --target all`.
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

    private fun agentSetupManifest(packageName: String, projectRoot: File) = buildJsonObject {
        put("schemaVersion", "1.0")
        put(
            "state",
            buildJsonObject {
                put("packageName", packageName)
                put("projectRoot", projectRoot.absolutePath)
                put("detectedAt", java.time.Instant.now().toString())
            },
        )
        put(
            "next",
            buildJsonArray {
                add(JsonPrimitive("./gradlew fixthisSetup"))
                add(JsonPrimitive("fixthis doctor --project-dir ${projectRoot.absolutePath} --json"))
                add(JsonPrimitive("# Restart Claude Code / Codex to reload MCP config"))
            },
        )
        put(
            "recovery",
            buildJsonObject {
                put("no-android-context", JsonPrimitive("Run from the Android repo root, or pass --allow-global to write the global codex config anyway."))
                put("no-app-module", JsonPrimitive("Run ./gradlew projects to list modules; pass the correct --package."))
                put("release-only-variant", JsonPrimitive("Add a debug variant; FixThis attaches debug builds only."))
                put("view-system-mixed", JsonPrimitive("Module contains View-based activities; migrate to ComponentActivity + setContent."))
                put("missing-application-id", JsonPrimitive("No unique applicationId; run from app module or pass --package."))
            },
        )
    }

    private data class WritePlan(val file: File, val content: String)
}
