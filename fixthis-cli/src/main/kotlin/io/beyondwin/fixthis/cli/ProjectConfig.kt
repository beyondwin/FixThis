package io.beyondwin.fixthis.cli

import kotlinx.serialization.Serializable
import java.io.File

object ProjectConfig {
    fun resolvePackageName(projectRoot: File, packageOverride: String?): String {
        packageOverride?.takeIf { it.isNotBlank() }?.let { return it }
        val projectConfig = projectRoot.resolve(".fixthis/project.json")
        return if (projectConfig.exists()) {
            val config = fixThisJson.decodeFromString(ProjectMetadata.serializer(), projectConfig.readText())
            config.applicationId.takeIf { it.isNotBlank() }
                ?: error("${projectConfig.path} does not contain applicationId")
        } else {
            GradleApplicationIdDetector.find(projectRoot) ?: error(
                "No package was provided, ${projectConfig.path} does not exist, " +
                    "and no unique Android applicationId was found in Gradle build files",
            )
        }
    }
}

object GradleApplicationIdDetector {
    private val applicationIdRegex =
        Regex("""(?m)\bapplicationId\b\s*(?:=|\s)\s*["']([^"']+)["']""")

    fun find(projectRoot: File): String? {
        val root = projectRoot.canonicalFile
        val matches = root
            .walkTopDown()
            .onEnter { directory -> shouldEnter(directory, root) }
            .filter { file -> file.isFile && file.name in GradleBuildFileNames }
            .mapNotNull(::applicationIdFrom)
            .distinct()
            .toList()

        return when (matches.size) {
            0 -> null
            1 -> matches.single()
            else -> error(
                "Multiple Android applicationId values found in Gradle build files: " +
                    matches.joinToString(", ") +
                    ". Pass --package explicitly.",
            )
        }
    }

    private fun shouldEnter(directory: File, root: File): Boolean {
        if (directory == root) return true
        return directory.name !in SkippedDirectoryNames
    }

    private fun applicationIdFrom(file: File): String? {
        val text = file.readText()
        return if (
            text.contains("applicationId") &&
            (text.contains("com.android.application") || text.contains("android {"))
        ) {
            applicationIdRegex.find(text)?.groupValues?.get(1)
        } else {
            null
        }
    }

    private val GradleBuildFileNames = setOf("build.gradle", "build.gradle.kts")
    private val SkippedDirectoryNames = setOf(
        ".git",
        ".gradle",
        ".fixthis",
        ".worktrees",
        "build",
        "node_modules",
    )
}

@Serializable
private data class ProjectMetadata(
    val schemaVersion: String = "1.0",
    val applicationId: String,
)
