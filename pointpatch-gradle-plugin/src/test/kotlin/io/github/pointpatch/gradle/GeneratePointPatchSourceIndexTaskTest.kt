package io.github.pointpatch.gradle

import io.github.pointpatch.gradle.task.GeneratePointPatchSourceIndexTask
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneratePointPatchSourceIndexTaskTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `scans kotlin source for compose source hints`() {
        val projectDir = temporaryFolder.newFolder("project")
        val sourceFile = projectDir.resolve("src/main/java/io/github/pointpatch/sample/SampleApp.kt")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(
            """
            package io.github.pointpatch.sample

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
        val outputDir = projectDir.resolve("build/generated/pointpatch/debug/assets")

        runTask(
            projectDir = projectDir,
            kotlinSources = listOf(sourceFile),
            resourceXmlFiles = emptyList(),
            outputDir = outputDir,
        )

        val index = Json.parseToJsonElement(
            outputDir.resolve("pointpatch/pointpatch-source-index.json").readText(),
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

        assertEquals("1.0", index.getValue("schemaVersion").jsonPrimitive.content)
        assertTrue(textValues.contains("Checkout title"))
        assertTrue(textValues.contains("Pay now"))
        assertTrue(stringResources.contains("checkout_total"))
        assertTrue(testTags.contains("pay_button"))
        assertTrue(symbols.contains("CheckoutScreen"))
        assertTrue(entries.any { entry ->
            val json = entry.jsonObject
            json.getValue("file").jsonPrimitive.content.endsWith("SampleApp.kt") &&
                json.getValue("line").jsonPrimitive.content.toInt() == 12 &&
                json.getValue("excerpt").jsonPrimitive.content.contains("Text(\"Pay now\"")
        })
    }

    @Test
    fun `scans xml string resources and writes build info`() {
        val projectDir = temporaryFolder.newFolder("project")
        val stringsFile = projectDir.resolve("src/main/res/values/strings.xml")
        stringsFile.parentFile.mkdirs()
        stringsFile.writeText(
            """
            <resources>
                <string name="app_name">PointPatch Sample</string>
                <string name="checkout_total">Total due</string>
            </resources>
            """.trimIndent(),
        )
        val outputDir = projectDir.resolve("build/generated/pointpatch/debug/assets")

        runTask(
            projectDir = projectDir,
            kotlinSources = emptyList(),
            resourceXmlFiles = listOf(stringsFile),
            outputDir = outputDir,
        )

        val index = Json.parseToJsonElement(
            outputDir.resolve("pointpatch/pointpatch-source-index.json").readText(),
        ).jsonObject
        val entries = index.getValue("entries").jsonArray
        val resources = entries.flatMap { entry ->
            entry.jsonObject.getValue("stringResources").jsonArray.map { it.jsonPrimitive.content }
        }
        val textValues = entries.flatMap { entry ->
            entry.jsonObject.getValue("text").jsonArray.map { it.jsonPrimitive.content }
        }
        val buildInfo = Json.parseToJsonElement(
            outputDir.resolve("pointpatch/pointpatch-build-info.json").readText(),
        ).jsonObject

        assertTrue(resources.contains("app_name"))
        assertTrue(resources.contains("checkout_total"))
        assertTrue(textValues.contains("PointPatch Sample"))
        assertTrue(textValues.contains("Total due"))
        assertEquals("debug", buildInfo.getValue("variantName").jsonPrimitive.content)
        assertEquals("0.1.0-test", buildInfo.getValue("runtimeVersion").jsonPrimitive.content)
        assertEquals("true", buildInfo.getValue("includeScreenshots").jsonPrimitive.content)
        assertEquals("true", buildInfo.getValue("redactEditableText").jsonPrimitive.content)
    }

    @Test
    fun `scans multiline raw kotlin string literals`() {
        val projectDir = temporaryFolder.newFolder("project")
        val sourceFile = projectDir.resolve("src/main/java/io/github/pointpatch/sample/RawStrings.kt")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(
            """
            package io.github.pointpatch.sample

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
        val outputDir = projectDir.resolve("build/generated/pointpatch/debug/assets")

        runTask(
            projectDir = projectDir,
            kotlinSources = listOf(sourceFile),
            resourceXmlFiles = emptyList(),
            outputDir = outputDir,
        )

        val entries = Json.parseToJsonElement(
            outputDir.resolve("pointpatch/pointpatch-source-index.json").readText(),
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

    private fun runTask(
        projectDir: File,
        kotlinSources: List<File>,
        resourceXmlFiles: List<File>,
        outputDir: File,
    ) {
        val project = ProjectBuilder.builder()
            .withProjectDir(projectDir)
            .build()
        val task = project.tasks.register(
            "generatePointPatchSourceIndex",
            GeneratePointPatchSourceIndexTask::class.java,
        ).get()
        task.projectDirectory.set(project.layout.projectDirectory)
        task.kotlinSourceFiles.from(kotlinSources)
        task.resourceXmlFiles.from(resourceXmlFiles)
        task.outputDirectory.set(outputDir)
        task.projectPath.set(":app")
        task.variantName.set("debug")
        task.runtimeVersion.set("0.1.0-test")
        task.includeScreenshots.set(true)
        task.redactEditableText.set(true)
        task.generateSourceIndex.set(true)
        task.generateProjectMetadata.set(true)

        task.generate()
    }
}
