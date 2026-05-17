package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadiness
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

private const val RecoveryNoAndroidContext =
    "Run from the Android repo root, or pass --allow-global to write the global codex config anyway."
private const val RecoveryNoAppModule =
    "Run ./gradlew projects to list modules; pass the correct --package."
private const val RecoveryReleaseOnlyVariant =
    "Add a debug variant; FixThis attaches debug builds only."
private const val RecoveryViewSystemMixed =
    "Module contains View-based activities; migrate to ComponentActivity + setContent."
private const val RecoveryMissingApplicationId =
    "No unique applicationId; run from app module or pass --package."
private const val ReadinessNeedsInstall =
    "Run `fixthis install-agent --project-dir . --target all`."
private const val ReadinessConfigRecoverable =
    "Run `fixthis install-agent --project-dir . --target all --dry-run`, " +
        "inspect the diff, then rerun without --dry-run."
private const val ReadinessEnvBlocker =
    "Install missing local prerequisites, then run `fixthis doctor --project-dir . --json`."
private const val ReadinessUnsupportedBuild =
    "Install a debuggable build with the FixThis sidekick enabled."
private const val ReadinessNeedsAppLaunch =
    "Launch the debug app or click Start in the feedback console."

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

    private fun agentSetupManifest(packageName: String, projectRoot: File): JsonObject {
        val readiness = FirstRunReadinessCatalog.configRecoverable(
            cause = "FixThis agent setup files were written; verify the debug app before opening the console.",
            details = mapOf("packageName" to packageName, "projectRoot" to projectRoot.absolutePath),
        ).copy(
            verify = "fixthis doctor --project-dir ${projectRoot.absolutePath} --json",
            fix = "Run doctor, restart the agent MCP client, then open the feedback console.",
            nextAction = "fixthis doctor --project-dir ${projectRoot.absolutePath} --json",
        )
        return buildJsonObject {
            put("schemaVersion", "1.0")
            put(
                "state",
                buildJsonObject {
                    put("packageName", packageName)
                    put("projectRoot", projectRoot.absolutePath)
                    put("detectedAt", java.time.Instant.now().toString())
                },
            )
            put("readiness", fixThisJson.encodeToJsonElement(FirstRunReadiness.serializer(), readiness).jsonObject)
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
                    put("no-android-context", JsonPrimitive(RecoveryNoAndroidContext))
                    put("no-app-module", JsonPrimitive(RecoveryNoAppModule))
                    put("release-only-variant", JsonPrimitive(RecoveryReleaseOnlyVariant))
                    put("view-system-mixed", JsonPrimitive(RecoveryViewSystemMixed))
                    put("missing-application-id", JsonPrimitive(RecoveryMissingApplicationId))
                    put(
                        "readinessRecovery",
                        buildJsonObject {
                            put("NEEDS_INSTALL", JsonPrimitive(ReadinessNeedsInstall))
                            put("CONFIG_RECOVERABLE", JsonPrimitive(ReadinessConfigRecoverable))
                            put("ENV_BLOCKER", JsonPrimitive(ReadinessEnvBlocker))
                            put("UNSUPPORTED_BUILD", JsonPrimitive(ReadinessUnsupportedBuild))
                            put("NEEDS_APP_LAUNCH", JsonPrimitive(ReadinessNeedsAppLaunch))
                        },
                    )
                },
            )
        }
    }

    private data class WritePlan(val file: File, val content: String)
}
