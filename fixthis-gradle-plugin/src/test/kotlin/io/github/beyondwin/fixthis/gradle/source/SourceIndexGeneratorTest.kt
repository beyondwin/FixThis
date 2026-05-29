package io.github.beyondwin.fixthis.gradle.source

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SourceIndexGeneratorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun kotlin(relativePath: String, body: String): File {
        val file = File(tempFolder.root, relativePath)
        file.parentFile.mkdirs()
        file.writeText(body)
        return file
    }

    private fun generate(files: List<File>): SourceIndexAsset {
        val generator = SourceIndexGenerator(
            projectDirectory = tempFolder.root,
            rootProjectDirectory = tempFolder.root,
            projectPath = ":app",
            json = Json { ignoreUnknownKeys = true },
        )
        return generator.generate(kotlinFiles = files, xmlFiles = emptyList())
    }

    @Test
    fun flagsReusedComponentDefinitionWithFanInCount() {
        val definition = kotlin(
            "ui/PrimaryButton.kt",
            """
            @Composable
            fun PrimaryButton(label: String) {}
            """.trimIndent(),
        )
        val callerA = kotlin(
            "ui/ScreenA.kt",
            """
            @Composable
            fun ScreenA() { PrimaryButton("Save") }
            """.trimIndent(),
        )
        val callerB = kotlin(
            "ui/ScreenB.kt",
            """
            @Composable
            fun ScreenB() { PrimaryButton("Cancel") }
            """.trimIndent(),
        )

        val asset = generate(listOf(definition, callerA, callerB))

        val definitionEntry = asset.entries.single { entry ->
            entry.signals.any { it.kind == SourceSignalKindAsset.COMPOSABLE_SYMBOL && it.value == "PrimaryButton" }
        }
        val sharedSignal = definitionEntry.signals.single { it.kind == SourceSignalKindAsset.SHARED_COMPONENT }
        assertEquals("2", sharedSignal.value)
    }

    @Test
    fun emitsCallSiteSignalsForReusedComponentDefinition() {
        val definition = kotlin(
            "ui/PrimaryButton.kt",
            """
            @Composable
            fun PrimaryButton(label: String) {}
            """.trimIndent(),
        )
        val callerA = kotlin(
            "ui/ScreenA.kt",
            """
            @Composable
            fun ScreenA() { PrimaryButton("Save") }
            """.trimIndent(),
        )
        val callerB = kotlin(
            "ui/ScreenB.kt",
            """
            @Composable
            fun ScreenB() { PrimaryButton("Cancel") }
            """.trimIndent(),
        )

        val asset = generate(listOf(definition, callerA, callerB))

        val definitionEntry = asset.entries.single { entry ->
            entry.signals.any { it.kind == SourceSignalKindAsset.COMPOSABLE_SYMBOL && it.value == "PrimaryButton" }
        }
        val callSiteValues = definitionEntry.signals
            .filter { it.kind == SourceSignalKindAsset.SHARED_COMPONENT_CALL_SITE }
            .map { it.value }
            .toSet()

        assertEquals(
            setOf("ui/ScreenA.kt:2\tScreenA\tSave", "ui/ScreenB.kt:2\tScreenB\tCancel"),
            callSiteValues,
        )
    }

    @Test
    fun doesNotEmitCallSiteSignalsForSingleUseDefinition() {
        val definition = kotlin(
            "ui/OnceCard.kt",
            """
            @Composable
            fun OnceCard() {}
            """.trimIndent(),
        )
        val caller = kotlin(
            "ui/Screen.kt",
            """
            @Composable
            fun Screen() { OnceCard() }
            """.trimIndent(),
        )

        val asset = generate(listOf(definition, caller))

        val definitionEntry = asset.entries.single { entry ->
            entry.signals.any { it.kind == SourceSignalKindAsset.COMPOSABLE_SYMBOL && it.value == "OnceCard" }
        }
        assertFalse(definitionEntry.signals.any { it.kind == SourceSignalKindAsset.SHARED_COMPONENT_CALL_SITE })
    }

    @Test
    fun doesNotFlagSingleUseComponentDefinition() {
        val definition = kotlin(
            "ui/OnceCard.kt",
            """
            @Composable
            fun OnceCard() {}
            """.trimIndent(),
        )
        val caller = kotlin(
            "ui/Screen.kt",
            """
            @Composable
            fun Screen() { OnceCard() }
            """.trimIndent(),
        )

        val asset = generate(listOf(definition, caller))

        val definitionEntry = asset.entries.single { entry ->
            entry.signals.any { it.kind == SourceSignalKindAsset.COMPOSABLE_SYMBOL && it.value == "OnceCard" }
        }
        assertFalse(definitionEntry.signals.any { it.kind == SourceSignalKindAsset.SHARED_COMPONENT })
    }
}
