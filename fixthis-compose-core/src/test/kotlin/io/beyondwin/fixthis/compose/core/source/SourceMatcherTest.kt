package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMatcherTest {
    @Test
    fun ranksSelectedNodeTextTagRoleAndActivityMatches() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/screens/ReviewScreen.kt",
                    line = 42,
                    symbols = listOf("ReviewScreen"),
                    text = listOf("Submit request"),
                    testTags = listOf("submitRequestButton"),
                    roles = listOf("Button"),
                    activityNames = listOf("MainActivity"),
                    excerpt = "Button(onClick = ...) { Text(\"Submit request\") }"
                ),
                SourceIndexEntry(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/screens/QueueScreen.kt",
                    line = 12,
                    symbols = listOf("QueueCard"),
                    text = listOf("Submit request"),
                    activityNames = listOf("MainActivity"),
                    excerpt = "Text(\"Submit request\")"
                )
            )
        )

        val matches = SourceMatcher(index).match(
            selectedNode = node(
                uid = "submit-request-button",
                text = listOf("Submit request"),
                role = "Button",
                testTag = "submitRequestButton",
                actions = listOf("OnClick")
            ),
            nearbyNodes = emptyList(),
            activityName = "io.beyondwin.fixthis.sample.MainActivity"
        )

        assertEquals("sample/src/main/java/io/beyondwin/fixthis/sample/screens/ReviewScreen.kt", matches.first().file)
        assertEquals(42, matches.first().line)
        assertEquals(SelectionConfidence.HIGH, matches.first().confidence)
        assertTrue(matches.first().matchedTerms.contains("Submit request"))
        assertTrue(matches.first().matchedTerms.contains("submitRequestButton"))
        assertTrue(matches.first().matchReasons.contains("selected text"))
        assertTrue(matches.first().matchReasons.contains("selected testTag"))
        assertTrue(matches.first().matchReasons.contains("selected role"))
        assertTrue(matches.first().matchReasons.contains("activity"))
    }

    @Test
    fun usesNearbyContextToRankGenericSelectedText() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/screens/ReviewScreen.kt",
                    line = 88,
                    symbols = listOf("PaymentErrorCard"),
                    text = listOf("Retry", "Payment failed"),
                    contentDescriptions = listOf("Payment failed"),
                    excerpt = "PaymentErrorCard { Text(\"Payment failed\"); Button { Text(\"Retry\") } }"
                ),
                SourceIndexEntry(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt",
                    line = 25,
                    symbols = listOf("RetryButton"),
                    text = listOf("Retry"),
                    excerpt = "Button { Text(\"Retry\") }"
                )
            )
        )

        val matches = SourceMatcher(index).match(
            selectedNode = node(uid = "retry", text = listOf("Retry"), role = "Button", actions = listOf("OnClick")),
            nearbyNodes = listOf(
                node(uid = "error-label", text = listOf("Payment failed")),
                node(uid = "error-icon", contentDescription = listOf("Payment failed"))
            ),
            activityName = null
        )

        assertEquals("sample/src/main/java/io/beyondwin/fixthis/sample/screens/ReviewScreen.kt", matches.first().file)
        assertTrue(matches.first().matchedTerms.contains("Payment failed"))
        assertTrue(matches.first().matchReasons.contains("nearby text"))
        assertTrue(matches.first().score > matches[1].score)
    }

    @Test
    fun limitsMatchesToFiveDeterministically() {
        val index = SourceIndex(
            entries = (1..8).map { number ->
                SourceIndexEntry(
                    file = "Screen$number.kt",
                    line = number,
                    symbols = listOf("SaveButton$number"),
                    text = listOf("Save"),
                    testTags = listOf("saveButton")
                )
            }
        )

        val matches = SourceMatcher(index).match(
            selectedNode = node(uid = "save", text = listOf("Save"), testTag = "saveButton"),
            nearbyNodes = emptyList(),
            activityName = null
        )

        assertEquals(5, matches.size)
        assertEquals(listOf("Screen1.kt", "Screen2.kt", "Screen3.kt", "Screen4.kt", "Screen5.kt"), matches.map { it.file })
    }

    @Test
    fun duplicateSelectedAndNearbyTermsDoNotIncreaseScoreOrConfidence() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "RetryScreen.kt",
                    line = 12,
                    text = listOf("Retry", "Payment failed"),
                    excerpt = "Text(\"Payment failed\"); Button { Text(\"Retry\") }"
                )
            )
        )

        val unique = SourceMatcher(index).match(
            selectedNode = node(uid = "retry", text = listOf("Retry")),
            nearbyNodes = listOf(node(uid = "error", text = listOf("Payment failed"))),
            activityName = null
        ).single()

        val duplicated = SourceMatcher(index).match(
            selectedNode = node(uid = "retry", text = listOf("Retry", "Retry")),
            nearbyNodes = listOf(
                node(uid = "error-1", text = listOf("Payment failed")),
                node(uid = "error-2", text = listOf("Payment failed"))
            ),
            activityName = null
        ).single()

        assertEquals(unique.score, duplicated.score, 0.0)
        assertEquals(unique.confidence, duplicated.confidence)
        assertEquals(unique.matchedTerms, duplicated.matchedTerms)
        assertEquals(unique.matchReasons, duplicated.matchReasons)
    }

    @Test
    fun conventionTestTagCanMatchComposableSymbol() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                        line = 12,
                        symbols = listOf("AppPrimaryButton"),
                        excerpt = "@Composable fun AppPrimaryButton(...)"
                    )
                )
            )
        )

        val matches = matcher.match(
            selectedNode = node(
                uid = "button",
                text = listOf("Sign In"),
                testTag = "comp:AppPrimaryButton:primary"
            ),
            nearbyNodes = emptyList(),
            activityName = "io.beyondwin.fixthis.sample.MainActivity"
        )

        assertEquals("sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt", matches.first().file)
        assertTrue(matches.first().matchReasons.contains("selected testTag convention composable"))
    }

    @Test
    fun conventionTestTagLiteralAndComposableMatchesDoNotStackScore() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                        line = 12,
                        symbols = listOf("AppPrimaryButton"),
                        testTags = listOf("comp:AppPrimaryButton:primary")
                    )
                )
            )
        )

        val match = matcher.match(
            selectedNode = node(
                uid = "button",
                testTag = "comp:AppPrimaryButton:primary"
            ),
            nearbyNodes = emptyList(),
            activityName = null
        ).single()

        assertEquals(SelectionConfidence.MEDIUM, match.confidence)
        assertEquals(0.65, match.score, 0.0)
        assertTrue(match.matchedTerms.contains("comp:AppPrimaryButton:primary"))
        assertTrue(match.matchedTerms.contains("AppPrimaryButton"))
        assertTrue(match.matchReasons.contains("selected testTag"))
        assertTrue(match.matchReasons.contains("selected testTag convention composable"))
    }

    @Test
    fun typedUiTextSignalOutranksArbitraryStringLiteralForSameSelectedText() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "LiteralOnly.kt",
                        line = 7,
                        signals = listOf(
                            SourceSignal(
                                kind = SourceSignalKind.ARBITRARY_STRING_LITERAL,
                                value = "Pay now",
                                confidenceWeight = 0.35
                            )
                        )
                    ),
                    SourceIndexEntry(
                        file = "CheckoutScreen.kt",
                        line = 12,
                        signals = listOf(
                            SourceSignal(
                                kind = SourceSignalKind.UI_TEXT,
                                value = "Pay now",
                                confidenceWeight = 1.0
                            )
                        )
                    )
                )
            )
        )

        val matches = matcher.match(
            selectedNode = node(uid = "pay", text = listOf("Pay now")),
            nearbyNodes = emptyList(),
            activityName = null
        )

        assertEquals("CheckoutScreen.kt", matches.first().file)
        assertEquals("LiteralOnly.kt", matches[1].file)
        assertTrue(matches.first().score > matches[1].score)
        assertTrue(matches.first().matchReasons.contains("selected text"))
    }

    @Test
    fun v1TextOnlyEntryStillMatchesSelectedText() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "LegacyCheckoutScreen.kt",
                        line = 21,
                        text = listOf("Pay now")
                    )
                )
            )
        )

        val matches = matcher.match(
            selectedNode = node(uid = "pay", text = listOf("Pay now")),
            nearbyNodes = emptyList(),
            activityName = null
        )

        assertEquals("LegacyCheckoutScreen.kt", matches.single().file)
        assertTrue(matches.single().matchReasons.contains("selected text"))
    }

    @Test
    fun mixedV2EntryFallsBackToLegacyRoleAndActivityMatches() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "CheckoutScreen.kt",
                        line = 30,
                        roles = listOf("Button"),
                        activityNames = listOf("MainActivity"),
                        signals = listOf(
                            SourceSignal(
                                kind = SourceSignalKind.UI_TEXT,
                                value = "Pay now",
                                confidenceWeight = 1.0
                            )
                        )
                    )
                )
            )
        )

        val match = matcher.match(
            selectedNode = node(uid = "pay", text = listOf("Pay now"), role = "Button"),
            nearbyNodes = emptyList(),
            activityName = "io.beyondwin.fixthis.sample.MainActivity"
        ).single()

        assertEquals("CheckoutScreen.kt", match.file)
        assertTrue(match.matchReasons.contains("selected text"))
        assertTrue(match.matchReasons.contains("selected role"))
        assertTrue(match.matchReasons.contains("activity"))
    }

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        contentDescription: List<String> = emptyList(),
        role: String? = null,
        testTag: String? = null,
        actions: List<String> = emptyList()
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(left = 0f, top = 0f, right = 100f, bottom = 50f),
        text = text,
        contentDescription = contentDescription,
        role = role,
        testTag = testTag,
        actions = actions
    )
}
