package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import java.io.File

internal data class SetupPlanRequest(
    val target: String,
    val projectRoot: File,
    val entry: McpConfigEntry,
)

internal data class SetupWritePlan(
    val writerName: String,
    val scope: String,
    val configFile: File,
    val content: String,
)

internal object SetupPlanner {
    fun selectedWriters(target: String): List<AgentConfigWriter> = when (target) {
        "codex" -> listOf(CodexConfigWriter())
        "claude" -> listOf(ClaudeConfigWriter())
        "cursor" -> listOf(CursorConfigWriter())
        "local" -> allWriters().filter { it.scope != "global" }
        else -> allWriters()
    }

    private fun allWriters(): List<AgentConfigWriter> = listOf(CodexConfigWriter(), ClaudeConfigWriter(), CursorConfigWriter())

    @Suppress("TooGenericExceptionCaught")
    fun buildWritePlans(
        writers: List<AgentConfigWriter>,
        projectRoot: File,
        entry: McpConfigEntry,
    ): List<SetupWritePlan> = writers.map { writer ->
        val configFile = writer.configFile(projectRoot)
        val merged = try {
            val current = configFile.takeIf { it.isFile }?.readText()
            writer.merge(current, entry)
        } catch (e: Exception) {
            throw CliktError(
                renderMergeFailure(writer.name, configFile, e),
                cause = e,
            )
        }
        SetupWritePlan(
            writerName = writer.name,
            scope = writer.scope,
            configFile = configFile,
            content = merged,
        )
    }
}
