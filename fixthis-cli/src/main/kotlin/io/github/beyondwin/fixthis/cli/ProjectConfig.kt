package io.github.beyondwin.fixthis.cli

import kotlinx.serialization.Serializable
import java.io.File

object ProjectConfig {
    fun resolve(projectRoot: File, packageOverride: String?): ResolvedProjectConfig {
        val projectConfig = projectRoot.resolve(".fixthis/project.json")
        val metadata = if (projectConfig.exists()) {
            fixThisJson.decodeFromString(ProjectMetadata.serializer(), projectConfig.readText())
        } else {
            null
        }
        return when {
            !packageOverride.isNullOrBlank() -> ResolvedProjectConfig(
                applicationId = packageOverride,
                projectPath = metadata?.projectPath?.takeIf(String::isNotBlank),
                variantName = metadata?.variantName?.takeIf(String::isNotBlank),
            )
            metadata != null -> ResolvedProjectConfig(
                applicationId = metadata.applicationId.takeIf { it.isNotBlank() }
                    ?: error("${projectConfig.path} does not contain applicationId"),
                projectPath = metadata.projectPath?.takeIf(String::isNotBlank),
                variantName = metadata.variantName?.takeIf(String::isNotBlank),
            )
            else -> ResolvedProjectConfig(
                applicationId = GradleApplicationIdDetector.find(projectRoot) ?: error(
                    "No package was provided, ${projectConfig.path} does not exist, " +
                        "and no unique Android applicationId was found in Gradle build files",
                ),
            )
        }
    }

    fun resolvePackageName(projectRoot: File, packageOverride: String?): String = resolve(projectRoot, packageOverride).applicationId
}

data class ResolvedProjectConfig(
    val applicationId: String,
    val projectPath: String? = null,
    val variantName: String? = null,
)

object GradleApplicationIdDetector {
    private val applicationIdRegex =
        Regex("""(?m)\bapplicationId\b\s*(?:=|\s)\s*["']([^"']+)["']""")
    private val applicationIdSuffixRegex =
        Regex("""(?m)\bapplicationIdSuffix\b\s*(?:=|\s)\s*["']([^"']+)["']""")

    fun find(projectRoot: File): String? {
        val matches = androidApplications(projectRoot)
            .flatMap { it.applicationIdCandidates() }
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
            .filter { it.matches(applicationId) }
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
                GradleAndroidApplication(
                    applicationId = applicationId,
                    applicationIdSuffixes = applicationIdSuffixRegex.findAll(text)
                        .map { match -> match.groupValues[1] }
                        .toList(),
                    buildFile = file.canonicalFile,
                )
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
    val applicationIdSuffixes: List<String> = emptyList(),
    val buildFile: File,
) {
    fun matches(candidate: String): Boolean = applicationId == candidate ||
        applicationIdSuffixes.any { suffix -> applicationId + suffix == candidate }

    fun applicationIdCandidates(): List<String> =
        (listOf(applicationId) + applicationIdSuffixes
            .filter { it.isNotBlank() }
            .map { suffix -> applicationId + suffix })
            .distinct()
}

@Serializable
private data class ProjectMetadata(
    val schemaVersion: String = "1.0",
    val applicationId: String,
    val projectPath: String? = null,
    val variantName: String? = null,
)
