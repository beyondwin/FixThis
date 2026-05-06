package io.github.pointpatch.compose.console.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class StudioShellApiTest {
    @Test
    fun feedbackConsoleScreenIsPublicEntrypointWithoutViewModelParameter() {
        val shellClass = Class.forName("io.github.pointpatch.compose.console.studio.StudioShellKt")
        val entrypoints = shellClass.methods.filter { it.name == "FeedbackConsoleScreen" }
        val publicMethodNames = shellClass.methods.map { it.name }.toSet()

        assertTrue("FeedbackConsoleScreen should be a public top-level function", entrypoints.isNotEmpty())
        assertTrue(
            "FeedbackConsoleScreen should not expose StudioViewModel in its public JVM signature",
            entrypoints.none { method ->
                method.parameterTypes.any { it.name == "io.github.pointpatch.compose.console.studio.StudioViewModel" }
            },
        )
        assertTrue(
            "FeedbackConsoleScreen should expose optional ScreenshotInfo preview input",
            entrypoints.any { method ->
                method.parameterTypes.any { it.name == "io.github.pointpatch.compose.core.model.ScreenshotInfo" }
            },
        )
        assertTrue("StudioShell should not be a public JVM helper", "StudioShell" !in publicMethodNames)
        assertTrue("StudioBody should not be a public JVM helper", "StudioBody" !in publicMethodNames)
    }

    @Test
    fun studioShellFilePublicMethodsOnlyExposeFeedbackConsoleScreenEntrypoint() {
        val shellClass = Class.forName("io.github.pointpatch.compose.console.studio.StudioShellKt")
        val publicDeclaredMethods = shellClass.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .filterNot { it.name.contains('$') }
            .map { it.name }
            .toSet()

        assertEquals(
            "StudioShell.kt should keep only the Kotlin source-level entrypoint public from that file",
            setOf("FeedbackConsoleScreen"),
            publicDeclaredMethods,
        )
    }

    @Test
    fun studioShellUsesOptionAEntrypointLayoutMetrics() {
        assertEquals(56, StudioShellDefaults.TopbarHeight.value.toInt())
        assertEquals(280, StudioShellDefaults.HistoryWidth.value.toInt())
        assertEquals(480, StudioShellDefaults.CanvasMinWidth.value.toInt())
        assertEquals(340, StudioShellDefaults.InspectorWidth.value.toInt())
        assertEquals(1100, StudioShellDefaults.MinimumBodyWidth.value.toInt())
    }
}
