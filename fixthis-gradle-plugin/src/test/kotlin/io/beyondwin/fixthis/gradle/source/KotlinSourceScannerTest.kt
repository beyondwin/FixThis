package io.beyondwin.fixthis.gradle.source

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KotlinSourceScannerTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `emits STRING_RESOURCE_RESOLVED when resolver has the resId`() {
        val kotlinFile = tempDir.newFile("LoginScreen.kt").apply {
            writeText(
                """
                package com.example
                import androidx.compose.material3.Text
                import androidx.compose.runtime.Composable
                import androidx.compose.ui.res.stringResource

                @Composable
                fun LoginScreen() {
                    Text(stringResource(R.string.login_button))
                }
                """.trimIndent(),
            )
        }
        val scanner = KotlinSourceScanner(tempDir.root, tempDir.root, json)
        val resolver = mapOf("login_button" to "로그인")

        val entries = scanner.scan(kotlinFile, resolver)

        val signals = entries.flatMap { it.signals }
        assertTrue(signals.any { it.kind == SourceSignalKindAsset.STRING_RESOURCE_RESOLVED && it.value == "로그인" })
        assertTrue(entries.any { "로그인" in it.text })
    }

    @Test
    fun `attaches LAMBDA_OWNER_FUNCTION signal to entries inside Composable`() {
        val file = tempDir.newFile("HomeScreen.kt").apply {
            writeText(
                """
                package com.example
                import androidx.compose.material3.Text
                import androidx.compose.runtime.Composable

                @Composable
                fun HomeScreen() {
                    Text("Hello")
                }

                fun helperThatIsNotComposable() {
                    println("not in scope")
                }
                """.trimIndent(),
            )
        }

        val entries = KotlinSourceScanner(tempDir.root, tempDir.root, json).scan(file)

        val helloEntry = entries.single { "Hello" in it.text }
        assertTrue(
            helloEntry.signals.any {
                it.kind == SourceSignalKindAsset.LAMBDA_OWNER_FUNCTION && it.value == "HomeScreen"
            },
        )
    }

    @Test
    fun `does not attach owner for entries outside any Composable`() {
        val file = tempDir.newFile("TopLevel.kt").apply {
            writeText(
                """
                package com.example
                const val LABEL = "outside"
                """.trimIndent(),
            )
        }

        val entries = KotlinSourceScanner(tempDir.root, tempDir.root, json).scan(file)

        entries.forEach { entry ->
            assertTrue(entry.signals.none { it.kind == SourceSignalKindAsset.LAMBDA_OWNER_FUNCTION })
        }
    }
}
