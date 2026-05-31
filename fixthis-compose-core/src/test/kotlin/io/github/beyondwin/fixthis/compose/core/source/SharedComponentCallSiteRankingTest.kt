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
                SourceLocationRef(file = "ui/ScreenB.kt", line = 20, mostLikely = true, recommendedEditSite = true),
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

    @Test
    fun marksMostLikelyWhenTopClearsSecondByExactlyTheMargin() {
        // ScreenB matches the literal "Save" (weight 2.0); ScreenA matches only the
        // enclosing-function name "Save" (weight 1.0). Delta = 2.0 - 1.0 = 1.0, exactly
        // CALL_SITE_MOST_LIKELY_MARGIN. This guards the `>=` comparison: a future edit to
        // `>` would stop marking the top at exactly the margin and break this test.
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/ScreenA.kt:10\tSave\tCancel",
                "ui/ScreenB.kt:20\tNothing\tSave",
            ),
            selectionTokens = setOf("save"),
        )

        assertEquals("ui/ScreenB.kt", ranked[0].file)
        assertEquals(true, ranked[0].mostLikely)
        assertEquals(true, ranked[0].recommendedEditSite)
        assertEquals(false, ranked[1].mostLikely)
    }

    @Test
    fun doesNotMarkMostLikelyWhenMarginIsBelowThreshold() {
        // Both sites match the literal "Save" and enclosing "Save" identically → margin 0.0.
        // Below CALL_SITE_MOST_LIKELY_MARGIN (1.0) → neither is marked; tie keeps static order.
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/ScreenA.kt:10\tSave\tSave",
                "ui/ScreenB.kt:20\tSave\tSave",
            ),
            selectionTokens = setOf("save"),
        )

        assertEquals(false, ranked[0].mostLikely)
        assertEquals(false, ranked[1].mostLikely)
        assertEquals("ui/ScreenA.kt", ranked[0].file) // tie keeps static order
    }

    @Test
    fun matchesMultiWordSelectionTokenAgainstCamelCaseEnclosingWord() {
        // Selection token "profile settings" (multi-word, e.g. from contentDescription).
        // Only the word "settings" overlaps "SettingsScreen". The OLD whole-string matcher
        // finds no contiguous substring either way → no match → no reorder. Word-aware
        // splitting makes "settings" == the "settings" word inside SettingsScreen → match.
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/Home.kt:10\tHomeScreen\tOpen",
                "ui/Settings.kt:20\tSettingsScreen\tOpen",
            ),
            selectionTokens = setOf("profile settings"),
        )

        assertEquals("ui/Settings.kt", ranked[0].file)
        assertEquals(true, ranked[0].mostLikely)
    }

    @Test
    fun matchesCamelCaseEnclosingAgainstSelectionWord() {
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/Home.kt:10\tHomeScreen\tOpen",
                "ui/Settings.kt:20\tProfileSettingsScreen\tOpen",
            ),
            selectionTokens = setOf("settings"),
        )

        assertEquals("ui/Settings.kt", ranked[0].file)
        assertEquals(true, ranked[0].mostLikely)
    }

    @Test
    fun matchesWhenSelectionTokenIsAWordInsideMultiWordLiteral() {
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/A.kt:10\tScreenA\tCancel",
                "ui/B.kt:20\tScreenB\tSave changes",
            ),
            selectionTokens = setOf("save"),
        )

        assertEquals("ui/B.kt", ranked[0].file)
        assertEquals(true, ranked[0].mostLikely)
    }

    @Test
    fun marksRecommendedEditSiteWhenTopClearsByMargin() {
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/A.kt:10\tScreenA\tCancel",
                "ui/B.kt:20\tScreenB\tSave changes",
            ),
            selectionTokens = setOf("save"),
        )

        assertEquals("ui/B.kt", ranked[0].file)
        assertEquals(true, ranked[0].mostLikely)
        assertEquals(true, ranked[0].recommendedEditSite)
        assertEquals(false, ranked[1].recommendedEditSite)
    }

    @Test
    fun doesNotRecommendEditSiteWithoutAConfidentMargin() {
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/A.kt:10\tScreenA\tSave",
                "ui/B.kt:20\tScreenB\tSave",
            ),
            selectionTokens = setOf("save"),
        )

        assertEquals(false, ranked[0].recommendedEditSite)
        assertEquals(false, ranked[1].recommendedEditSite)
    }
}
