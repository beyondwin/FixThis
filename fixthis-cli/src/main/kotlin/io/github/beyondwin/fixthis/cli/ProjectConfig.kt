package io.github.beyondwin.fixthis.cli

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
        val matches = androidApplications(projectRoot)
            .map { it.applicationId }
            .distinct()

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

    fun findApplication(projectRoot: File, applicationId: String): GradleAndroidApplication? {
        val matches = androidApplications(projectRoot)
            .filter { it.applicationId == applicationId }
            .distinctBy { it.buildFile.canonicalFile }

        return when (matches.size) {
            0 -> null
            1 -> matches.single()
            else -> error(
                "Multiple Android app modules declare applicationId $applicationId: " +
                    matches.joinToString(", ") { it.buildFile.path } +
                    ". Apply the FixThis Gradle plugin manually.",
            )
        }
    }

    private fun androidApplications(projectRoot: File): List<GradleAndroidApplication> {
        val root = projectRoot.canonicalFile
        return root
            .walkTopDown()
            .onEnter { directory -> shouldEnter(directory, root) }
            .filter { file -> file.isFile && file.name in GradleBuildFileNames }
            .mapNotNull(::androidApplicationFrom)
            .toList()
    }

    private fun shouldEnter(directory: File, root: File): Boolean {
        if (directory == root) return true
        return directory.name !in SkippedDirectoryNames
    }

    private fun androidApplicationFrom(file: File): GradleAndroidApplication? {
        val text = file.readText()
        return if (
            text.contains("applicationId") &&
            (text.contains("com.android.application") || text.contains("android {"))
        ) {
            applicationIdRegex.find(text)?.groupValues?.get(1)?.let { applicationId ->
                GradleAndroidApplication(applicationId = applicationId, buildFile = file.canonicalFile)
            }
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

data class GradleAndroidApplication(
    val applicationId: String,
    val buildFile: File,
)

@Serializable
private data class ProjectMetadata(
    val schemaVersion: String = "1.0",
    val applicationId: String,
)
