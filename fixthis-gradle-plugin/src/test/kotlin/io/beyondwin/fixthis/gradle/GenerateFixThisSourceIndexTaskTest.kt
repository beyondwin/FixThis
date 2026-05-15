package io.beyondwin.fixthis.gradle

import io.beyondwin.fixthis.gradle.task.GenerateFixThisSourceIndexTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.tasks.CacheableTask
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GenerateFixThisSourceIndexTaskTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `scans kotlin source for compose source hints`() {
        val projectDir = temporaryFolder.newFolder("project")
        val sourceFile = projectDir.resolve("src/main/java/io/github/fixthis/sample/SampleApp.kt")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(
            """
            package io.beyondwin.fixthis.sample

            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.platform.testTag
            import androidx.compose.ui.res.stringResource

            @Composable
            fun CheckoutScreen() {
                val title = "Checkout title"
                Text("Pay now", modifier = Modifier.testTag("pay_button"))
                Text(stringResource(R.string.checkout_total))
            }
            """.trimIndent(),
        )
        val outputDir = projectDir.resolve("build/generated/fixthis/debug/assets")

        runTask(
            projectDir = projectDir,
            kotlinSources = listOf(sourceFile),
            resourceXmlFiles = emptyList(),
            outputDir = outputDir,
        )

        val index = Json.parseToJsonElement(
            outputDir.resolve("fixthis/fixthis-source-index.json").readText(),
        ).jsonObject
        val entries = index.getValue("entries").jsonArray
        val textValues = entries.flatMap { entry ->
            entry.jsonObject.getValue("text").jsonArray.map { it.jsonPrimitive.content }
        }
        val stringResources = entries.flatMap { entry ->
            entry.jsonObject.getValue("stringResources").jsonArray.map { it.jsonPrimitive.content }
        }
        val testTags = entries.flatMap { entry ->
            entry.jsonObject.getValue("testTags").jsonArray.map { it.jsonPrimitive.content }
        }
        val symbols = entries.flatMap { entry ->
            entry.jsonObject.getValue("symbols").jsonArray.map { it.jsonPrimitive.content }
        }

        assertEquals("1.2", index.getValue("schemaVersion").jsonPrimitive.content)
        assertTrue(textValues.contains("Checkout title"))
        assertTrue(textValues.contains("Pay now"))
        assertTrue(stringResources.contains("checkout_total"))
        assertTrue(testTags.contains("pay_button"))
        assertTrue(symbols.contains("CheckoutScreen"))
        assertTrue(
            entries.any { entry ->
                val json = entry.jsonObject
                json.getValue("file").jsonPrimitive.content.endsWith("SampleApp.kt") &&
                    json.getValue("line").jsonPrimitive.content.toInt() == 12 &&
                    json.getValue("excerpt").jsonPrimitive.content.contains("Text(\"Pay now\"")
            },
        )
    }

    @Test
    fun `writes source root and repo relative source paths for module project`() {
        val rootDir = temporaryFolder.newFolder("repo")
        val projectDir = rootDir.resolve("sample")
        val sourceFile = projectDir.resolve("src/main/java/io/example/Sample.kt")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(
            """
            package io.example

            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable

            @Composable
            fun Sample() {
                Text("Hello")
            }
            """.trimIndent(),
        )
        val outputDir = projectDir.resolve("build/generated/fixthis/debug/assets")

        runTask(
            projectDir = projectDir,
            rootProjectDir = rootDir,
            kotlinSources = listOf(sourceFile),
            resourceXmlFiles = emptyList(),
            outputDir = outputDir,
        )

        val index = Json.parseToJsonElement(
            outputDir.resolve("fixthis/fixthis-source-index.json").readText(),
        ).jsonObject
        val sourceRoot = index.getValue("sourceRoot").jsonObject
        val entry = index.getValue("entries").jsonArray
            .map { it.jsonObject }
            .single { entry ->
                entry.getValue("symbols").jsonArray.any { symbol ->
                    symbol.jsonPrimitive.content == "Sample"
                }
            }

        assertEquals("1.2", index.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("gradle-project", sourceRoot.getValue("kind").jsonPrimitive.content)
        assertEquals(":app", sourceRoot.getValue("gradlePath").jsonPrimitive.content)
        assertEquals("sample", sourceRoot.getValue("projectDir").jsonPrimitive.content)
        assertEquals("src/main/java/io/example/Sample.kt", entry.getValue("file").jsonPrimitive.content)
        assertEquals("sample/src/main/java/io/example/Sample.kt", entry.getValue("repoFile").jsonPrimitive.content)
    }

    @Test
    fun `scans xml string resources and writes build info`() {
        val projectDir = temporaryFolder.newFolder("project")
        val stringsFile = projectDir.resolve("src/main/res/values/strings.xml")
        stringsFile.parentFile.mkdirs()
        stringsFile.writeText(
            """
            <resources>
                <string name="app_name">FixThis</string>
                <string name="checkout_total">Total due</string>
            </resources>
            """.trimIndent(),
        )
        val outputDir = projectDir.resolve("build/generated/fixthis/debug/assets")

        runTask(
            projectDir = projectDir,
            kotlinSources = emptyList(),
            resourceXmlFiles = listOf(stringsFile),
            outputDir = outputDir,
        )

        val index = Json.parseToJsonElement(
            outputDir.resolve("fixthis/fixthis-source-index.json").readText(),
        ).jsonObject
        val entries = index.getValue("entries").jsonArray
        val resources = entries.flatMap { entry ->
            entry.jsonObject.getValue("stringResources").jsonArray.map { it.jsonPrimitive.content }
        }
        val textValues = entries.flatMap { entry ->
            entry.jsonObject.getValue("text").jsonArray.map { it.jsonPrimitive.content }
        }
        val buildInfo = Json.parseToJsonElement(
            outputDir.resolve("fixthis/fixthis-build-info.json").readText(),
        ).jsonObject

        assertTrue(resources.contains("app_name"))
        assertTrue(resources.contains("checkout_total"))
        assertTrue(textValues.contains("FixThis"))
        assertTrue(textValues.contains("Total due"))
        assertEquals("debug", buildInfo.getValue("variantName").jsonPrimitive.content)
        assertEquals("0.1.0-test", buildInfo.getValue("runtimeVersion").jsonPrimitive.content)
        assertEquals("fixthis/fixthis-source-index.json", buildInfo.getValue("sourceIndexAsset").jsonPrimitive.content)
        assertEquals("fixthis/fixthis-build-info.json", buildInfo.getValue("buildInfoAsset").jsonPrimitive.content)
        assertEquals("true", buildInfo.getValue("includeScreenshots").jsonPrimitive.content)
        assertEquals("true", buildInfo.getValue("redactEditableText").jsonPrimitive.content)
    }

    @Test
    fun `emits typed source index signals while preserving v1 fields`() {
        val projectDir = temporaryFolder.newFolder("project")
        val sourceFile = projectDir.resolve("src/main/java/io/github/fixthis/sample/CheckoutFeature.kt")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(
            """
            package io.beyondwin.fixthis.sample

            import androidx.compose.foundation.layout.Box
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.platform.testTag
            import androidx.compose.ui.res.stringResource
            import androidx.compose.ui.semantics.contentDescription
            import androidx.compose.ui.semantics.semantics

            class CheckoutFeature {
                @Composable
                fun CheckoutScreen() {
                    val analyticsName = "checkout_debug"
                    Text("Pay now", modifier = Modifier.testTag("comp:CheckoutScreen:primary"))
                    Box(Modifier.testTag("plain_tag").semantics { contentDescription = "Payment options" })
                    Text(stringResource(R.string.checkout_total))
                }
            }
            """.trimIndent(),
        )
        val stringsFile = projectDir.resolve("src/main/res/values/strings.xml")
        stringsFile.parentFile.mkdirs()
        stringsFile.writeText(
            """
            <resources>
                <string name="checkout_total">Total due</string>
            </resources>
            """.trimIndent(),
        )
        val outputDir = projectDir.resolve("build/generated/fixthis/debug/assets")

        runTask(
            projectDir = projectDir,
            kotlinSources = listOf(sourceFile),
            resourceXmlFiles = listOf(stringsFile),
            outputDir = outputDir,
        )

        val entries = Json.parseToJsonElement(
            outputDir.resolve("fixthis/fixthis-source-index.json").readText(),
        ).jsonObject.getValue("entries").jsonArray
        val textValues = entries.flatMap { entry ->
            entry.jsonObject.getValue("text").jsonArray.map { it.jsonPrimitive.content }
        }
        val stringResources = entries.flatMap { entry ->
            entry.jsonObject.getValue("stringResources").jsonArray.map { it.jsonPrimitive.content }
        }
        val testTags = entries.flatMap { entry ->
            entry.jsonObject.getValue("testTags").jsonArray.map { it.jsonPrimitive.content }
        }
        val contentDescriptions = entries.flatMap { entry ->
            entry.jsonObject.getValue("contentDescriptions").jsonArray.map { it.jsonPrimitive.content }
        }
        val symbols = entries.flatMap { entry ->
            entry.jsonObject.getValue("symbols").jsonArray.map { it.jsonPrimitive.content }
        }
        val signals = entries.flatMap { entry ->
            entry.jsonObject.getValue("signals").jsonArray.map { signal ->
                signal.jsonObject.getValue("kind").jsonPrimitive.content to
                    signal.jsonObject.getValue("value").jsonPrimitive.content
            }
        }

        assertTrue(textValues.contains("checkout_debug"))
        assertTrue(textValues.contains("Pay now"))
        assertTrue(textValues.contains("Total due"))
        assertTrue(stringResources.contains("checkout_total"))
        assertTrue(testTags.contains("comp:CheckoutScreen:primary"))
        assertTrue(testTags.contains("plain_tag"))
        assertTrue(contentDescriptions.contains("Payment options"))
        assertTrue(symbols.contains("CheckoutScreen"))
        assertTrue(signals.contains("COMPOSABLE_SYMBOL" to "CheckoutScreen"))
        assertTrue(signals.contains("UI_TEXT" to "Pay now"))
        assertTrue(signals.contains("UI_TEXT" to "Total due"))
        assertTrue(signals.contains("STRING_RESOURCE" to "checkout_total"))
        assertTrue(signals.contains("STRING_RESOURCE_RESOLVED" to "Total due"))
        assertTrue(signals.contains("LAMBDA_OWNER_FUNCTION" to "CheckoutScreen"))
        assertTrue(signals.contains("STRICT_COMP_TEST_TAG" to "comp:CheckoutScreen:primary"))
        assertTrue(signals.contains("TEST_TAG" to "comp:CheckoutScreen:primary"))
        assertTrue(signals.contains("TEST_TAG" to "plain_tag"))
        assertTrue(signals.contains("CONTENT_DESCRIPTION" to "Payment options"))
        assertTrue(signals.contains("ARBITRARY_STRING_LITERAL" to "checkout_debug"))
        assertTrue(
            entries.any { entry ->
                val json = entry.jsonObject
                json.getValue("signals").jsonArray.any { signal ->
                    signal.jsonObject.getValue("value").jsonPrimitive.content == "Pay now"
                } &&
                    json.getValue("packageName").jsonPrimitive.content == "io.beyondwin.fixthis.sample" &&
                    json.getValue("className").jsonPrimitive.content == "CheckoutFeature"
            },
        )
        assertTrue(
            entries.any { entry ->
                val json = entry.jsonObject
                json.getValue("signals").jsonArray.any { signal ->
                    signal.jsonObject.getValue("value").jsonPrimitive.content == "Total due"
                } &&
                    json.getValue("packageName").jsonPrimitive.contentOrNull == null &&
                    json.getValue("className").jsonPrimitive.contentOrNull == null
            },
        )
    }

    @Test
    fun `scans multiline raw kotlin string literals`() {
        val projectDir = temporaryFolder.newFolder("project")
        val sourceFile = projectDir.resolve("src/main/java/io/github/fixthis/sample/RawStrings.kt")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(
            """
            package io.beyondwin.fixthis.sample

            import androidx.compose.material3.Text
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.platform.testTag
            import androidx.compose.ui.semantics.contentDescription

            fun RawStrings() {
                val title = ""${'"'}
                    Account
                    summary
                ""${'"'}.trimIndent()
                Text(
                    ""${'"'}
                    Pay
                    later
                    ""${'"'}.trimIndent(),
                    modifier = Modifier.testTag(
                        ""${'"'}
                        pay_later_button
                        ""${'"'}.trimIndent(),
                    ),
                )
                val modifier = Modifier.semantics {
                    contentDescription = ""${'"'}
                        Payment options
                    ""${'"'}.trimIndent()
                }
            }
            """.trimIndent(),
        )
        val outputDir = projectDir.resolve("build/generated/fixthis/debug/assets")

        runTask(
            projectDir = projectDir,
            kotlinSources = listOf(sourceFile),
            resourceXmlFiles = emptyList(),
            outputDir = outputDir,
        )

        val entries = Json.parseToJsonElement(
            outputDir.resolve("fixthis/fixthis-source-index.json").readText(),
        ).jsonObject.getValue("entries").jsonArray
        val textValues = entries.flatMap { entry ->
            entry.jsonObject.getValue("text").jsonArray.map { it.jsonPrimitive.content }
        }
        val testTags = entries.flatMap { entry ->
            entry.jsonObject.getValue("testTags").jsonArray.map { it.jsonPrimitive.content }
        }
        val contentDescriptions = entries.flatMap { entry ->
            entry.jsonObject.getValue("contentDescriptions").jsonArray.map { it.jsonPrimitive.content }
        }

        assertTrue(textValues.any { it.contains("Account") && it.contains("summary") })
        assertTrue(textValues.any { it.contains("Pay") && it.contains("later") })
        assertTrue(testTags.any { it.contains("pay_later_button") })
        assertTrue(contentDescriptions.any { it.contains("Payment options") })
    }

    @Test
    fun `task is explicitly cacheable`() {
        assertTrue(
            "GenerateFixThisSourceIndexTask must be annotated with @CacheableTask so Gradle can reuse output bytes",
            GenerateFixThisSourceIndexTask::class.java.isAnnotationPresent(CacheableTask::class.java),
        )
    }

    @Test
    fun `removes stale source index output when source index generation is disabled`() {
        val projectDir = temporaryFolder.newFolder("project")
        val sourceFile = projectDir.resolve("src/main/java/io/github/fixthis/sample/SampleApp.kt")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(
            """
            package io.beyondwin.fixthis.sample

            import androidx.compose.material3.Text

            fun SampleApp() {
                Text("First")
            }
            """.trimIndent(),
        )
        val outputDir = projectDir.resolve("build/generated/fixthis/debug/assets")

        runTask(
            projectDir = projectDir,
            kotlinSources = listOf(sourceFile),
            resourceXmlFiles = emptyList(),
            outputDir = outputDir,
        )
        val sourceIndex = outputDir.resolve("fixthis/fixthis-source-index.json")
        assertTrue(sourceIndex.isFile)

        runTask(
            projectDir = projectDir,
            kotlinSources = listOf(sourceFile),
            resourceXmlFiles = emptyList(),
            outputDir = outputDir,
            generateSourceIndex = false,
            generateProjectMetadata = true,
        )

        assertFalse("stale source index JSON must not remain after generation is disabled", sourceIndex.exists())
        assertTrue(outputDir.resolve("fixthis/fixthis-build-info.json").isFile)
    }

    @Test
    fun `removes stale build info output when metadata generation is disabled`() {
        val projectDir = temporaryFolder.newFolder("project")
        val stringsFile = projectDir.resolve("src/main/res/values/strings.xml")
        stringsFile.parentFile.mkdirs()
        stringsFile.writeText(
            """
            <resources>
                <string name="app_name">FixThis</string>
            </resources>
            """.trimIndent(),
        )
        val outputDir = projectDir.resolve("build/generated/fixthis/debug/assets")

        runTask(
            projectDir = projectDir,
            kotlinSources = emptyList(),
            resourceXmlFiles = listOf(stringsFile),
            outputDir = outputDir,
        )
        val buildInfo = outputDir.resolve("fixthis/fixthis-build-info.json")
        assertTrue(buildInfo.isFile)

        runTask(
            projectDir = projectDir,
            kotlinSources = emptyList(),
            resourceXmlFiles = listOf(stringsFile),
            outputDir = outputDir,
            generateSourceIndex = true,
            generateProjectMetadata = false,
        )

        assertTrue(outputDir.resolve("fixthis/fixthis-source-index.json").isFile)
        assertFalse("stale build info JSON must not remain after metadata generation is disabled", buildInfo.exists())
    }

    private fun runTask(
        projectDir: File,
        rootProjectDir: File = projectDir,
        projectPath: String = ":app",
        kotlinSources: List<File>,
        resourceXmlFiles: List<File>,
        outputDir: File,
        generateSourceIndex: Boolean = true,
        generateProjectMetadata: Boolean = true,
    ) {
        val project = ProjectBuilder.builder()
            .withProjectDir(projectDir)
            .build()
        val task = project.tasks.register(
            "generateFixThisSourceIndex",
            GenerateFixThisSourceIndexTask::class.java,
        ).get()
        task.projectDirectory.set(project.layout.projectDirectory)
        task.rootProjectDirectory.set(rootProjectDir)
        task.kotlinSourceFiles.from(kotlinSources)
        task.resourceXmlFiles.from(resourceXmlFiles)
        task.outputDirectory.set(outputDir)
        task.projectPath.set(projectPath)
        task.variantName.set("debug")
        task.runtimeVersion.set("0.1.0-test")
        task.includeScreenshots.set(true)
        task.redactEditableText.set(true)
        task.generateSourceIndex.set(generateSourceIndex)
        task.generateProjectMetadata.set(generateProjectMetadata)

        task.generate()
    }
}
