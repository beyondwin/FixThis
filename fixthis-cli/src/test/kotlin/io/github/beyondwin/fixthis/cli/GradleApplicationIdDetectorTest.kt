package io.github.beyondwin.fixthis.cli

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GradleApplicationIdDetectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun findsApplicationWhenFlavorAndBuildTypeSuffixesAreCombined() {
        val root = temporaryFolder.newFolder()
        root.resolve("settings.gradle.kts").writeText("""include(":app")""")
        val buildFile = root.resolve("app/build.gradle.kts")
        buildFile.parentFile.mkdirs()
        buildFile.writeText(
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
                buildTypes {
                    debug {
                        applicationIdSuffix = ".debug"
                    }
                }
            }
            """.trimIndent(),
        )

        val application = GradleApplicationIdDetector.findApplication(
            projectRoot = root,
            applicationId = "com.example.agent.demo.debug",
        )

        assertEquals(buildFile.canonicalFile, application?.buildFile)
    }

    @Test
    fun findsApplicationWhenSuffixBlocksAreDeclaredInDifferentOrder() {
        val root = temporaryFolder.newFolder()
        root.resolve("settings.gradle.kts").writeText("""include(":app")""")
        val buildFile = root.resolve("app/build.gradle.kts")
        buildFile.parentFile.mkdirs()
        buildFile.writeText(
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
                buildTypes {
                    debug {
                        applicationIdSuffix = ".debug"
                    }
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

        val application = GradleApplicationIdDetector.findApplication(
            projectRoot = root,
            applicationId = "com.example.agent.demo.debug",
        )

        assertEquals(buildFile.canonicalFile, application?.buildFile)
    }
}
