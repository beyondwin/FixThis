package io.github.beyondwin.fixthis.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectConfigTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolvesPackageFromOverrideBeforeProjectConfig() {
        val root = temporaryFolder.newFolder()
        root.resolve(".fixthis").mkdirs()
        root.resolve(".fixthis/project.json").writeText(
            """{"schemaVersion":"1.0","applicationId":"io.github.beyondwin.fixthis.fromfile"}""",
        )

        assertEquals(
            "io.github.beyondwin.fixthis.override",
            ProjectConfig.resolvePackageName(root, "io.github.beyondwin.fixthis.override"),
        )
    }

    @Test
    fun resolvesPackageFromProjectConfig() {
        val root = temporaryFolder.newFolder()
        root.resolve(".fixthis").mkdirs()
        root.resolve(".fixthis/project.json").writeText(
            """{"schemaVersion":"1.0","applicationId":"io.github.beyondwin.fixthis.sample"}""",
        )

        assertEquals("io.github.beyondwin.fixthis.sample", ProjectConfig.resolvePackageName(root, null))
    }

    @Test
    fun resolvesPackageFromGradleKotlinApplicationIdWhenProjectConfigIsMissing() {
        val root = temporaryFolder.newFolder()
        root.resolve("settings.gradle.kts").writeText("""include(":app")""")
        root.resolve("app").mkdirs()
        root.resolve("app/build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                id("io.github.beyondwin.fixthis.compose")
            }

            android {
                namespace = "com.example.agent"
                defaultConfig {
                    applicationId = "com.example.agent.debug"
                }
            }
            """.trimIndent(),
        )

        assertEquals("com.example.agent.debug", ProjectConfig.resolvePackageName(root, null))
    }

    @Test
    fun refusesToGuessPackageWhenGradleApplicationIdSuffixCreatesMultipleCandidates() {
        val root = temporaryFolder.newFolder()
        root.resolve("settings.gradle.kts").writeText("""include(":app")""")
        root.resolve("app").mkdirs()
        root.resolve("app/build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                id("io.github.beyondwin.fixthis.compose")
            }

            android {
                namespace = "com.example.agent"
                flavorDimensions += "mode"
                defaultConfig {
                    applicationId = "com.example.agent"
                }
                productFlavors {
                    create("demo") {
                        dimension = "mode"
                        applicationIdSuffix = ".demo"
                    }
                }
            }
            """.trimIndent(),
        )

        try {
            ProjectConfig.resolvePackageName(root, null)
            fail("expected package resolution to require --package")
        } catch (error: IllegalStateException) {
            assertTrue(
                "expected ambiguous package message, got: ${error.message}",
                error.message!!.contains("Multiple Android applicationId values found") &&
                    error.message!!.contains("com.example.agent") &&
                    error.message!!.contains("com.example.agent.demo") &&
                    error.message!!.contains("Pass --package explicitly"),
            )
        }
    }

    @Test
    fun projectConfigStillWinsOverGradleApplicationId() {
        val root = temporaryFolder.newFolder()
        root.resolve(".fixthis").mkdirs()
        root.resolve(".fixthis/project.json").writeText(
            """{"schemaVersion":"1.0","applicationId":"io.github.beyondwin.fixthis.fromfile"}""",
        )
        root.resolve("app").mkdirs()
        root.resolve("app/build.gradle.kts").writeText(
            """
            android {
                defaultConfig {
                    applicationId = "com.example.agent.debug"
                }
            }
            """.trimIndent(),
        )

        assertEquals("io.github.beyondwin.fixthis.fromfile", ProjectConfig.resolvePackageName(root, null))
    }
}
