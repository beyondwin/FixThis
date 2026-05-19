package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.FixThisRelease
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GradlePluginInstallerMessageTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun noAppModuleFailureUsesTemplatedMessage() {
        val root = tempFolder.newFolder("empty-project")
        val captured = mutableListOf<String>()
        try {
            GradlePluginInstaller.apply(
                projectRoot = root,
                packageName = "com.example.missing",
                pluginVersion = "0.3.0",
                dryRun = true,
                echo = { line -> captured += line },
            )
        } catch (e: Exception) {
            captured += e.message.orEmpty()
        }
        val joined = captured.joinToString("\n")
        assertTrue(
            "Expected templated failure, got:\n$joined",
            joined.contains("verify: ./gradlew projects") &&
                joined.contains("fix:    pass --package"),
        )
    }

    @Test
    fun warnsWhenPublishedRuntimeRequiresHigherCompileSdkThanDetectedCatalogValue() {
        val root = tempFolder.newFolder("compile-sdk-project")
        File(root, "settings.gradle.kts").writeText("""include(":app")""")
        File(root, "gradle").mkdirs()
        File(root, "gradle/libs.versions.toml").writeText(
            """
            [versions]
            compileSdk = "35"
            """.trimIndent(),
        )
        File(root, "app").mkdirs()
        File(root, "app/build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
            }

            android {
                defaultConfig {
                    applicationId = "com.example.compile"
                }
            }
            """.trimIndent(),
        )
        val captured = mutableListOf<String>()

        GradlePluginInstaller.apply(
            projectRoot = root,
            packageName = "com.example.compile",
            pluginVersion = "0.6.0",
            dryRun = true,
            echo = { line -> captured += line },
        )

        val joined = captured.joinToString("\n")
        assertTrue(
            "Expected compileSdk warning, got:\n$joined",
            joined.contains("compileSdk 36") && joined.contains("detected compileSdk 35"),
        )
    }

    @Test
    fun currentRuntimeDoesNotWarnAtDocumentedCompileSdkFloor() {
        val root = tempFolder.newFolder("current-runtime-project")
        File(root, "settings.gradle.kts").writeText("""include(":app")""")
        File(root, "gradle").mkdirs()
        File(root, "gradle/libs.versions.toml").writeText(
            """
            [versions]
            androidCompileSdk = "34"
            """.trimIndent(),
        )
        File(root, "app").mkdirs()
        File(root, "app/build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
            }

            android {
                defaultConfig {
                    applicationId = "com.example.current"
                }
            }
            """.trimIndent(),
        )
        val captured = mutableListOf<String>()

        GradlePluginInstaller.apply(
            projectRoot = root,
            packageName = "com.example.current",
            pluginVersion = FixThisRelease.VERSION,
            dryRun = true,
            echo = { line -> captured += line },
        )

        val joined = captured.joinToString("\n")
        assertFalse(
            "Current FixThis runtime should stay consumable at compileSdk 34, got:\n$joined",
            joined.contains("requires Android compileSdk"),
        )
    }
}
