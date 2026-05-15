package io.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import io.beyondwin.fixthis.cli.GradleApplicationIdDetector
import java.io.File

internal object GradlePluginInstaller {
    const val PluginId = "io.beyondwin.fixthis.compose"
    const val DefaultPluginVersion = "0.2.0"

    fun apply(
        projectRoot: File,
        packageName: String,
        pluginVersion: String,
        dryRun: Boolean,
        echo: (String) -> Unit,
    ) {
        val application = GradleApplicationIdDetector.findApplication(projectRoot, packageName)
            ?: throw CliktError(
                "Could not find an Android app module for $packageName. " +
                    "Apply Gradle plugin `$PluginId` manually.",
            )
        val plan = patchPlan(application.buildFile, pluginVersion)
        if (plan == null) {
            echo("Gradle plugin already applied: ${application.buildFile.absolutePath}")
            return
        }
        if (dryRun) {
            echo("Would update ${application.buildFile.absolutePath}")
            echo(plan.newText.trimEnd())
            return
        }
        application.buildFile.writeText(plan.newText)
        echo("Applied Gradle plugin `$PluginId` to ${application.buildFile.absolutePath}")
    }

    private fun patchPlan(buildFile: File, pluginVersion: String): PatchPlan? {
        val text = buildFile.readText()
        if (text.contains(PluginId)) return null
        val patched = when (buildFile.name) {
            "build.gradle.kts" -> patchKotlinDsl(text, pluginVersion)
            "build.gradle" -> patchGroovyDsl(text, pluginVersion)
            else -> null
        } ?: throw CliktError(
            "Could not find a plugins block in ${buildFile.absolutePath}. " +
                "Apply Gradle plugin `$PluginId` manually.",
        )
        return PatchPlan(patched)
    }

    private fun patchKotlinDsl(text: String, pluginVersion: String): String? = patchPluginsBlock(text) { indent ->
        """$indent    id("$PluginId") version "$pluginVersion""""
    }

    private fun patchGroovyDsl(text: String, pluginVersion: String): String? = patchPluginsBlock(text) { indent ->
        """$indent    id '$PluginId' version '$pluginVersion'"""
    }

    private fun patchPluginsBlock(
        text: String,
        pluginLine: (String) -> String,
    ): String? {
        val match = Regex("""(?m)^(\s*)plugins\s*\{""").find(text)
        val insertAt = match?.let { text.indexOf('\n', startIndex = it.range.last) } ?: -1
        return if (match == null || insertAt == -1) {
            null
        } else {
            val lineIndent = match.groupValues[1]
            text.replaceRange(insertAt + 1, insertAt + 1, pluginLine(lineIndent) + "\n")
        }
    }

    private data class PatchPlan(val newText: String)
}
