package io.github.beyondwin.fixthis.gradle.source

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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

    @Test
    fun `does not index arbitrary strings outside Composable as UI source candidates`() {
        val file = tempDir.newFile("SeedData.kt").apply {
            writeText(
                """
                package com.example
                import androidx.compose.material3.Text
                import androidx.compose.runtime.Composable

                val seedItems = listOf("Seed fixture")

                @Composable
                fun CatalogCard() {
                    Text("Open")
                }
                """.trimIndent(),
            )
        }

        val entries = KotlinSourceScanner(tempDir.root, tempDir.root, json).scan(file)

        assertTrue(entries.none { "Seed fixture" in it.text })
        assertTrue(
            entries.none { entry ->
                entry.signals.any {
                    it.kind == SourceSignalKindAsset.ARBITRARY_STRING_LITERAL && it.value == "Seed fixture"
                }
            },
        )
        assertTrue(entries.any { "Open" in it.text })
    }

    @Test
    fun `treats named Text string argument as UI text instead of arbitrary literal`() {
        val file = tempDir.newFile("NamedText.kt").apply {
            writeText(
                """
                package com.example
                import androidx.compose.material3.Text
                import androidx.compose.runtime.Composable

                @Composable
                fun PriceDeltaIndicator() {
                    Text(
                        text = "-",
                    )
                }
                """.trimIndent(),
            )
        }

        val entries = KotlinSourceScanner(tempDir.root, tempDir.root, json).scan(file)

        val textEntry = entries.single { "-" in it.text }
        assertTrue(textEntry.signals.any { it.kind == SourceSignalKindAsset.UI_TEXT && it.value == "-" })
        assertTrue(textEntry.signals.none { it.kind == SourceSignalKindAsset.ARBITRARY_STRING_LITERAL && it.value == "-" })
    }

    @Test
    fun `indexes stringResource backed content descriptions and roles`() {
        val file = tempDir.newFile("Toolbar.kt").apply {
            writeText(
                """
                package com.example
                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.semantics.Role
                import androidx.compose.ui.semantics.contentDescription
                import androidx.compose.ui.semantics.role
                import androidx.compose.ui.semantics.semantics
                import androidx.compose.ui.res.stringResource

                @Composable
                fun Toolbar() {
                    val refreshLabel = stringResource(R.string.refresh)
                    IconButton(
                        modifier = Modifier.semantics {
                            contentDescription = refreshLabel
                            role = Role.Button
                        },
                    )
                    IconButton(
                        contentDescription = stringResource(R.string.settings),
                    )
                }
                """.trimIndent(),
            )
        }

        val entries = KotlinSourceScanner(tempDir.root, tempDir.root, json).scan(
            file = file,
            stringResourceResolver = mapOf(
                "refresh" to "새로고침",
                "settings" to "설정",
            ),
        )

        val contentDescriptions = entries.flatMap { it.contentDescriptions }
        val roles = entries.flatMap { it.roles }
        val signals = entries.flatMap { it.signals }

        assertEquals(listOf("새로고침", "설정"), contentDescriptions)
        assertEquals(listOf("Button"), roles)
        assertTrue(signals.any { it.kind == SourceSignalKindAsset.CONTENT_DESCRIPTION && it.value == "새로고침" })
        assertTrue(signals.any { it.kind == SourceSignalKindAsset.CONTENT_DESCRIPTION && it.value == "설정" })
        assertTrue(signals.any { it.kind == SourceSignalKindAsset.ROLE && it.value == "Button" })
    }

    @Test
    fun `indexes typed stringResource variable content descriptions`() {
        val file = tempDir.newFile("TypedToolbar.kt").apply {
            writeText(
                """
                package com.example
                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.semantics.contentDescription
                import androidx.compose.ui.semantics.semantics
                import androidx.compose.ui.res.stringResource

                @Composable
                fun Toolbar() {
                    val refreshLabel: String = stringResource(R.string.refresh)
                    IconButton(
                        modifier = Modifier.semantics {
                            contentDescription = refreshLabel
                        },
                    )
                }
                """.trimIndent(),
            )
        }

        val entries = KotlinSourceScanner(tempDir.root, tempDir.root, json).scan(
            file = file,
            stringResourceResolver = mapOf("refresh" to "새로고침"),
        )

        val contentDescriptions = entries.flatMap { it.contentDescriptions }
        val signals = entries.flatMap { it.signals }

        assertEquals(listOf("새로고침"), contentDescriptions)
        assertTrue(signals.any { it.kind == SourceSignalKindAsset.CONTENT_DESCRIPTION && it.value == "새로고침" })
    }

    @Test
    fun `indexes Layout and SubcomposeLayout renderer calls with owner function`() {
        val file = tempDir.newFile("AdaptiveGrid.kt").apply {
            writeText(
                """
                package com.example
                import androidx.compose.runtime.Composable
                import androidx.compose.ui.layout.Layout
                import androidx.compose.ui.layout.SubcomposeLayout

                @Composable
                fun AdaptiveGrid() {
                    Layout(content = {}, measurePolicy = { _, _ -> layout(0, 0) {} })
                }

                @Composable
                fun DeferredTabs() {
                    SubcomposeLayout { _, _ -> layout(0, 0) {} }
                }
                """.trimIndent(),
            )
        }

        val entries = KotlinSourceScanner(tempDir.root, tempDir.root, json).scan(file)
        val layoutEntry = entries.single { entry ->
            entry.signals.any { it.kind == SourceSignalKindAsset.LAYOUT_RENDERER && it.value == "Layout" }
        }
        val subcomposeEntry = entries.single { entry ->
            entry.signals.any { it.kind == SourceSignalKindAsset.LAYOUT_RENDERER && it.value == "SubcomposeLayout" }
        }

        assertTrue(layoutEntry.signals.any { it.kind == SourceSignalKindAsset.LAMBDA_OWNER_FUNCTION && it.value == "AdaptiveGrid" })
        assertTrue(subcomposeEntry.signals.any { it.kind == SourceSignalKindAsset.LAMBDA_OWNER_FUNCTION && it.value == "DeferredTabs" })
    }
}
