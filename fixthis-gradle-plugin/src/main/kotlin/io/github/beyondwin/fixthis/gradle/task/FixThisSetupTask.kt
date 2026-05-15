package io.github.beyondwin.fixthis.gradle.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class FixThisSetupTask : DefaultTask() {
    @get:Internal
    abstract val rootProjectDirectory: DirectoryProperty

    @get:Input
    abstract val projectPath: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val applicationId: Property<String>

    @TaskAction
    fun setup() {
        val root = rootProjectDirectory.get().asFile
        val fixThisDirectory = root.resolve(".fixthis")
        fixThisDirectory.mkdirs()

        fixThisDirectory.resolve("project.json").writeText(
            json.encodeToString(
                FixThisProjectMetadata(
                    applicationId = applicationId.get(),
                    projectPath = projectPath.get(),
                    variantName = variantName.get(),
                ),
            ) + "\n",
        )
        fixThisDirectory.resolve("agent-setup.md").writeText(agentSetupGuide())
        fixThisDirectory.resolve("mcp.json.template").writeText(
            json.encodeToString(projectScopedMcpTemplate()) + "\n",
        )

        logger.lifecycle("Wrote ${fixThisDirectory.resolve("project.json").absolutePath}")
        logger.lifecycle("Wrote ${fixThisDirectory.resolve("mcp.json.template").absolutePath}")
        logger.lifecycle("Next: fixthis init --project-dir .")
        logger.lifecycle("Then: fixthis doctor --project-dir .")
    }

    private fun agentSetupGuide(): String = """
        # FixThis Agent Setup

        This Android project has FixThis project metadata for `${applicationId.get()}`.

        To finish agent setup from the project root:

        1. Run `fixthis init --project-dir .`.
        2. Run `fixthis doctor --project-dir .`.
        3. Use MCP tool `fixthis_open_feedback_console`.

        If `fixthis` is not installed, install the FixThis CLI/MCP package first,
        then rerun the commands above.
    """.trimIndent() + "\n"

    private fun projectScopedMcpTemplate() = buildJsonObject {
        put(
            "mcpServers",
            buildJsonObject {
                put(
                    "fixthis",
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

    private companion object {
        val json: Json = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

@Serializable
private data class FixThisProjectMetadata(
    val schemaVersion: String = "1.0",
    val applicationId: String,
    val projectPath: String,
    val variantName: String,
)
