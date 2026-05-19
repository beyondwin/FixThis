package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import io.github.beyondwin.fixthis.cli.FixThisRelease
import io.github.beyondwin.fixthis.cli.GradleAndroidApplication
import io.github.beyondwin.fixthis.cli.GradleApplicationIdDetector
import java.io.File

internal object GradlePluginInstaller {
    const val PluginId = "io.github.beyondwin.fixthis.compose"
    val DefaultPluginVersion: String
        get() = FixThisRelease.VERSION

    fun apply(
        projectRoot: File,
        packageName: String,
        pluginVersion: String,
        dryRun: Boolean,
        echo: (String) -> Unit,
    ) {
        val (application, plan) = applicationPatchPlan(projectRoot, packageName, pluginVersion)
        RuntimeCompileSdkCompatibility.emitWarning(projectRoot, application.buildFile, pluginVersion, echo)
        if (plan == null) {
            SetupRunResults.applied.get() += InstallAgentJsonReport.Applied(
                target = "gradle-plugin",
                path = application.buildFile.absolutePath,
                scope = "project-local",
            )
            echo("Gradle plugin already applied: ${application.buildFile.absolutePath}")
            return
        }
        if (dryRun) {
            echo("Would update ${application.buildFile.absolutePath}")
            echo(plan.newText.trimEnd())
            return
        }
        application.buildFile.writeText(plan.newText)
        SetupRunResults.applied.get() += InstallAgentJsonReport.Applied(
            target = "gradle-plugin",
            path = application.buildFile.absolutePath,
            scope = "project-local",
        )
        echo("Applied Gradle plugin `$PluginId` to ${application.buildFile.absolutePath}")
    }

    fun preflight(
        projectRoot: File,
        packageName: String,
        pluginVersion: String,
    ) {
        applicationPatchPlan(projectRoot, packageName, pluginVersion)
    }

    private fun applicationPatchPlan(
        projectRoot: File,
        packageName: String,
        pluginVersion: String,
    ): ApplicationPatchPlan {
        val application = GradleApplicationIdDetector.findApplication(projectRoot, packageName)
            ?: throw CliktError(AgentSurfaceMessages.noAppModule(packageName = packageName))
        return ApplicationPatchPlan(application, patchPlan(application.buildFile, pluginVersion))
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

    private data class ApplicationPatchPlan(
        val application: GradleAndroidApplication,
        val patch: PatchPlan?,
    )

    private data class PatchPlan(val newText: String)
}

private object RuntimeCompileSdkCompatibility {
    private const val BrokenRuntimeVersion = "0.6.0"
    private const val BrokenRuntimeMinCompileSdk = 36

    fun emitWarning(
        projectRoot: File,
        buildFile: File,
        pluginVersion: String,
        echo: (String) -> Unit,
    ) {
        val required = minimumCompileSdkForRuntime(pluginVersion) ?: return
        val detected = detectCompileSdk(projectRoot, buildFile) ?: return
        if (detected < required) {
            echo(
                "Warning: FixThis runtime $pluginVersion requires Android compileSdk $required or newer; " +
                    "detected compileSdk $detected. Update the app compileSdk before building the debug variant.",
            )
        }
    }

    private fun minimumCompileSdkForRuntime(pluginVersion: String): Int? = when (pluginVersion) {
        BrokenRuntimeVersion -> BrokenRuntimeMinCompileSdk
        else -> null
    }

    private fun detectCompileSdk(projectRoot: File, buildFile: File): Int? = directCompileSdk(buildFile) ?: versionCatalogCompileSdk(projectRoot)

    private fun directCompileSdk(buildFile: File): Int? {
        val text = buildFile.readText()
        val kotlinDsl = Regex("""(?m)\bcompileSdk\s*=\s*(\d+)""").find(text)
        val groovyDsl = Regex("""(?m)\bcompileSdk\s+(\d+)""").find(text)
        return (kotlinDsl ?: groovyDsl)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun versionCatalogCompileSdk(projectRoot: File): Int? {
        val catalog = projectRoot.resolve("gradle/libs.versions.toml").takeIf { it.isFile } ?: return null
        val text = catalog.readText()
        return listOf("compileSdk", "androidCompileSdk")
            .firstNotNullOfOrNull { key ->
                Regex("(?m)^$key\\s*=\\s*\"(\\d+)\"")
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            }
    }
}
