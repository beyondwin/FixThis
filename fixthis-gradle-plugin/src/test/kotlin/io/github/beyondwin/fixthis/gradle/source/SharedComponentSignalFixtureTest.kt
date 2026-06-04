package io.github.beyondwin.fixthis.gradle.source

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharedComponentSignalFixtureTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun kotlin(relativePath: String, body: String): File {
        val file = File(tempFolder.root, relativePath)
        file.parentFile.mkdirs()
        file.writeText(body)
        return file
    }

    @Test
    fun reusedComponentDefinitionEmitsSharedComponentSignal() {
        val definition = kotlin(
            "src/main/java/app/PrimaryButton.kt",
            """
            package app
            import androidx.compose.runtime.Composable
            @Composable fun PrimaryButton(label: String) {}
            """.trimIndent(),
        )
        val home = kotlin(
            "src/main/java/app/HomeScreen.kt",
            """
            package app
            import androidx.compose.runtime.Composable
            @Composable fun HomeScreen() { PrimaryButton("Save"); PrimaryButton("Cancel") }
            """.trimIndent(),
        )
        val checkout = kotlin(
            "src/main/java/app/CheckoutScreen.kt",
            """
            package app
            import androidx.compose.runtime.Composable
            @Composable fun CheckoutScreen() { PrimaryButton("Pay") }
            """.trimIndent(),
        )

        val generator = SourceIndexGenerator(
            projectDirectory = tempFolder.root,
            rootProjectDirectory = tempFolder.root,
            projectPath = ":app",
            json = Json { ignoreUnknownKeys = true },
        )
        val index = generator.generate(
            kotlinFiles = listOf(definition, home, checkout),
            xmlFiles = emptyList(),
        )

        val buttonEntry = index.entries.single { entry ->
            entry.file.endsWith("PrimaryButton.kt")
        }
        assertTrue(
            "expected SHARED_COMPONENT signal on reused PrimaryButton definition; got ${buttonEntry.signals}",
            buttonEntry.signals.any { it.kind == SourceSignalKindAsset.SHARED_COMPONENT },
        )
    }
}
