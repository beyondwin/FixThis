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

    @Test
    fun recordsCallSiteLocationsByFileAndLine() {
        val sources = listOf(
            CallSiteSource(
                path = "ui/ScreenA.kt",
                content = "@Composable\nfun ScreenA() {\n  PrimaryButton(\"Save\")\n}\n",
            ),
            CallSiteSource(
                path = "ui/ScreenB.kt",
                content = "@Composable\nfun ScreenB() {\n  PrimaryButton(\"Cancel\")\n}\n",
            ),
        )

        val sites = composableCallSites(sources, definitionNames = setOf("PrimaryButton"))

        assertEquals(
            listOf(
                ComposableCallSite(file = "ui/ScreenA.kt", line = 3),
                ComposableCallSite(file = "ui/ScreenB.kt", line = 3),
            ),
            sites["PrimaryButton"],
        )
    }

    @Test
    fun callSiteLocationsExcludeDeclarationsStringsAndMemberCalls() {
        val sources = listOf(
            CallSiteSource(
                path = "ui/PrimaryButton.kt",
                content = "@Composable\nfun PrimaryButton(label: String) {}\n",
            ),
            CallSiteSource(
                path = "ui/Screen.kt",
                content =
                "@Composable\n" +
                    "fun Screen() {\n" +
                    "  val s = \"PrimaryButton(\"\n" + // string literal, ignored
                    "  obj.PrimaryButton(\"x\")\n" + // member call, ignored
                    "  PrimaryButton(\"real\")\n" + // real call site, line 5
                    "}\n",
            ),
        )

        val sites = composableCallSites(sources, definitionNames = setOf("PrimaryButton"))

        assertEquals(listOf(ComposableCallSite(file = "ui/Screen.kt", line = 5)), sites["PrimaryButton"])
    }
}
