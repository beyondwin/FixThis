package io.github.beyondwin.fixthis.gradle.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ComposableCallSiteFanInTest {
    @Test
    fun countsDistinctCallSitesAcrossSourcesAndExcludesDeclaration() {
        val definitionSource = """
            @Composable
            fun PrimaryButton(label: String) { Text(label) }
        """.trimIndent()
        val callerOne = """
            @Composable
            fun ScreenA() {
                PrimaryButton("Save")
                PrimaryButton("Cancel")
            }
        """.trimIndent()
        val callerTwo = """
            @Composable
            fun ScreenB() { PrimaryButton("Delete") }
        """.trimIndent()

        val counts = composableCallSiteCounts(
            sources = listOf(definitionSource, callerOne, callerTwo),
            definitionNames = setOf("PrimaryButton"),
        )

        // 3 invocations; the `fun PrimaryButton(` declaration is not counted.
        assertEquals(3, counts["PrimaryButton"])
    }

    @Test
    fun ignoresCallsInStringsCommentsAndQualifiedReceivers() {
        val source = """
            // PrimaryButton("commented out")
            @Composable
            fun Screen() {
                val note = "PrimaryButton(\"in a string\")"
                theme.PrimaryButton("qualified-call")
                PrimaryButton("real")
            }
        """.trimIndent()

        val counts = composableCallSiteCounts(
            sources = listOf(source),
            definitionNames = setOf("PrimaryButton"),
        )

        assertEquals(1, counts["PrimaryButton"])
    }

    @Test
    fun returnsNoEntryForSingleUseAndUnknownNames() {
        val source = """
            @Composable
            fun Once() {}
            @Composable
            fun Screen() { Once() }
        """.trimIndent()

        val counts = composableCallSiteCounts(
            sources = listOf(source),
            definitionNames = setOf("Once", "NeverDefined"),
        )

        assertEquals(1, counts["Once"])
        assertNull(counts["NeverDefined"])
        assertFalse(SHARED_COMPONENT_FANIN_THRESHOLD <= (counts["Once"] ?: 0))
    }
}
