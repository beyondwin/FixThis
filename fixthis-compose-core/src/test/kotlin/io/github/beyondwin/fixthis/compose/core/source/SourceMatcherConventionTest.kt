package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMatcherConventionTest {
    @Test
    fun matcherRecognizesCustomConventionFromHeader() {
        val index = SourceIndex(
            schemaVersion = "1.3",
            testTagConventions = listOf("^([A-Za-z][A-Za-z0-9]*)_([A-Za-z0-9-]+)$"),
            entries = listOf(
                SourceIndexEntry(
                    file = "app/MyScreen.kt",
                    line = 10,
                    signals = listOf(SourceSignal(SourceSignalKind.COMPOSABLE_SYMBOL, "MyScreen")),
                ),
            ),
        )

        val matches = SourceMatcher(index).match(node("MyScreen_button"), emptyList(), null)

        assertTrue(matches.any { it.file == "app/MyScreen.kt" })
    }

    @Test
    fun matcherFallsBackToDefaultWhenHeaderEmpty() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "app/PrimaryButton.kt",
                    line = 5,
                    signals = listOf(SourceSignal(SourceSignalKind.COMPOSABLE_SYMBOL, "PrimaryButton")),
                ),
            ),
        )

        val matches = SourceMatcher(index).match(node("comp:PrimaryButton:checkout"), emptyList(), null)

        assertTrue(matches.any { it.file == "app/PrimaryButton.kt" })
    }

    private fun node(testTag: String) = FixThisNode(
        uid = "n",
        composeNodeId = 1,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 0f, 10f, 10f),
        testTag = testTag,
        path = listOf("root"),
    )
}
