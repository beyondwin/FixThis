package io.github.beyondwin.fixthis.cli

import io.github.beyondwin.fixthis.cli.commands.GradlePluginInstaller
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseVersionSyncTest {
    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "gradle.properties").isFile && File(it, "settings.gradle.kts").isFile }

    @Test
    fun `runtime code versions come from gradle properties`() {
        val version = runtimeBuildVersion()

        assertEquals(version, FIXTHIS_CLI_VERSION)
        assertEquals(version, GradlePluginInstaller.DefaultPluginVersion)
    }

    @Test
    fun `runtime code does not hardcode public release versions`() {
        val offenders = listOf(
            "fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/CliVersion.kt",
            "fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/GradlePluginInstaller.kt",
            "fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/FixThisExtension.kt",
        ).mapNotNull { path ->
            val text = File(repoRoot, path).readText()
            if (hardcodedReleaseVersionRegex.containsMatchIn(text)) path else null
        }

        assertTrue(
            offenders.isEmpty(),
            "Public release versions must be generated from FIXTHIS_VERSION, not hardcoded:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `public install metadata and current docs use the gradle properties version`() {
        val version = gradlePropertiesVersion()
        val mismatches = currentReleaseFiles.flatMap { path ->
            val text = File(repoRoot, path).readText()
            releaseVersionRegex.findAll(text)
                .map { it.value }
                .filter { it.removePrefix("v") != version }
                .map { "$path contains $it, expected $version" }
                .toList()
        }

        assertTrue(
            mismatches.isEmpty(),
            "Current public install metadata must use FIXTHIS_VERSION:\n" + mismatches.joinToString("\n"),
        )
    }

    private fun runtimeBuildVersion(): String =
        System.getProperty("fixthis.version")
            ?: gradlePropertiesVersion()

    private fun gradlePropertiesVersion(): String = File(repoRoot, "gradle.properties")
        .readLines()
        .first { it.startsWith("FIXTHIS_VERSION=") }
        .substringAfter("=")

    private companion object {
        val hardcodedReleaseVersionRegex =
            Regex("""(?:FIXTHIS_CLI_VERSION|DefaultPluginVersion|DefaultFixThisRuntimeVersion)\s*(?::\s*String)?\s*=\s*"[0-9]""")
        val releaseVersionRegex = Regex("""(?<![0-9.])v?0\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?""")
        val currentReleaseFiles = listOf(
            "README.md",
            "MCP.md",
            "server.json",
            "npm/fixthis/package.json",
            "docs/getting-started/add-to-your-app.md",
            "docs/getting-started/agent-install-snippet.md",
            "docs/getting-started/connect-your-agent.md",
            "docs/reference/cli.md",
            "docs/contributing/release-readiness.md",
            "docs/architecture/overview.md",
            "docs/releases/unreleased.md",
        )
    }
}
