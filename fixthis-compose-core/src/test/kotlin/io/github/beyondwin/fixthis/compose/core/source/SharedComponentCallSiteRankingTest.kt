package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.SourceLocationRef
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedComponentCallSiteRankingTest {
    @Test
    fun ranksLiteralMatchFirstAndMarksMostLikely() {
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/ScreenA.kt:10\tScreenA\tCancel",
                "ui/ScreenB.kt:20\tProfileScreen\tSave|Profile",
            ),
            selectionTokens = setOf("save"),
        )

        assertEquals(
            listOf(
                SourceLocationRef(file = "ui/ScreenB.kt", line = 20, mostLikely = true),
                SourceLocationRef(file = "ui/ScreenA.kt", line = 10, mostLikely = false),
            ),
            ranked,
        )
    }

    @Test
    fun keepsStaticOrderAndNoMarkWhenNoEvidenceMatches() {
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/ScreenA.kt:10\tScreenA\tCancel",
                "ui/ScreenB.kt:20\tScreenB\tDelete",
            ),
            selectionTokens = setOf("save"),
        )

        assertEquals(
            listOf(
                SourceLocationRef(file = "ui/ScreenA.kt", line = 10, mostLikely = false),
                SourceLocationRef(file = "ui/ScreenB.kt", line = 20, mostLikely = false),
            ),
            ranked,
        )
    }

    @Test
    fun parsesPlainLocationWithoutContext() {
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf("ui/ScreenA.kt:10", "ui/ScreenB.kt"),
            selectionTokens = emptySet(),
        )

        assertEquals(
            listOf(
                SourceLocationRef(file = "ui/ScreenA.kt", line = 10, mostLikely = false),
                SourceLocationRef(file = "ui/ScreenB.kt", line = null, mostLikely = false),
            ),
            ranked,
        )
    }

    @Test
    fun doesNotMarkMostLikelyWhenTopMatchesTie() {
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/ScreenA.kt:10\tScreenA\tSave",
                "ui/ScreenB.kt:20\tScreenB\tSave",
            ),
            selectionTokens = setOf("save"),
        )

        assertEquals(false, ranked[0].mostLikely)
        assertEquals(false, ranked[1].mostLikely)
        assertEquals("ui/ScreenA.kt", ranked[0].file)
    }
}
