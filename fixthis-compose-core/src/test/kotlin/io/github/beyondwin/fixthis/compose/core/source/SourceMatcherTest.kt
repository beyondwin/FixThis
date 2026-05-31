package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength
import io.github.beyondwin.fixthis.compose.core.model.SourceLocationRef
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SAMPLE_SOURCE_PREFIX = "sample/src/main/java/io/github/beyondwin/fixthis/sample"
private const val SAMPLE_REVIEW_SCREEN = "$SAMPLE_SOURCE_PREFIX/screens/ReviewScreen.kt"

@Suppress("LargeClass")
class SourceMatcherTest {
    @Test
    fun ranksSelectedNodeTextTagRoleAndActivityMatches() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = SAMPLE_REVIEW_SCREEN,
                    line = 42,
                    symbols = listOf("ReviewScreen"),
                    text = listOf("Submit request"),
                    testTags = listOf("submitRequestButton"),
                    roles = listOf("Button"),
                    activityNames = listOf("MainActivity"),
                    excerpt = "Button(onClick = ...) { Text(\"Submit request\") }",
                ),
                SourceIndexEntry(
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/QueueScreen.kt",
                    line = 12,
                    symbols = listOf("QueueCard"),
                    text = listOf("Submit request"),
                    activityNames = listOf("MainActivity"),
                    excerpt = "Text(\"Submit request\")",
                ),
            ),
        )

        val matches = SourceMatcher(index).match(
            selectedNode = node(
                uid = "submit-request-button",
                text = listOf("Submit request"),
                role = "Button",
                testTag = "submitRequestButton",
                actions = listOf("OnClick"),
            ),
            nearbyNodes = emptyList(),
            activityName = "io.github.beyondwin.fixthis.sample.MainActivity",
        )

        assertEquals(SAMPLE_REVIEW_SCREEN, matches.first().file)
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
                    file = SAMPLE_REVIEW_SCREEN,
                    line = 88,
                    symbols = listOf("PaymentErrorCard"),
                    text = listOf("Retry", "Payment failed"),
                    contentDescriptions = listOf("Payment failed"),
                    excerpt = "PaymentErrorCard { Text(\"Payment failed\"); Button { Text(\"Retry\") } }",
                ),
                SourceIndexEntry(
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt",
                    line = 25,
                    symbols = listOf("RetryButton"),
                    text = listOf("Retry"),
                    excerpt = "Button { Text(\"Retry\") }",
                ),
            ),
        )

        val matches = SourceMatcher(index).match(
            selectedNode = node(uid = "retry", text = listOf("Retry"), role = "Button", actions = listOf("OnClick")),
            nearbyNodes = listOf(
                node(uid = "error-label", text = listOf("Payment failed")),
                node(uid = "error-icon", contentDescription = listOf("Payment failed")),
            ),
            activityName = null,
        )

        assertEquals(SAMPLE_REVIEW_SCREEN, matches.first().file)
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
                    testTags = listOf("saveButton"),
                )
            },
        )

        val matches = SourceMatcher(index).match(
            selectedNode = node(uid = "save", text = listOf("Save"), testTag = "saveButton"),
            nearbyNodes = emptyList(),
            activityName = null,
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
                    excerpt = "Text(\"Payment failed\"); Button { Text(\"Retry\") }",
                ),
            ),
        )

        val unique = SourceMatcher(index).match(
            selectedNode = node(uid = "retry", text = listOf("Retry")),
            nearbyNodes = listOf(node(uid = "error", text = listOf("Payment failed"))),
            activityName = null,
        ).single()

        val duplicated = SourceMatcher(index).match(
            selectedNode = node(uid = "retry", text = listOf("Retry", "Retry")),
            nearbyNodes = listOf(
                node(uid = "error-1", text = listOf("Payment failed")),
                node(uid = "error-2", text = listOf("Payment failed")),
            ),
            activityName = null,
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
                        file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                        line = 12,
                        symbols = listOf("AppPrimaryButton"),
                        excerpt = "@Composable fun AppPrimaryButton(...)",
                    ),
                ),
            ),
        )

        val matches = matcher.match(
            selectedNode = node(
                uid = "button",
                text = listOf("Sign In"),
                testTag = "comp:AppPrimaryButton:primary",
            ),
            nearbyNodes = emptyList(),
            activityName = "io.github.beyondwin.fixthis.sample.MainActivity",
        )

        assertEquals("sample/src/main/java/io/github/beyondwin/fixthis/sample/components/AppPrimaryButton.kt", matches.first().file)
        assertTrue(matches.first().matchReasons.contains("selected testTag convention composable"))
    }

    @Test
    fun selectedTextCandidateOutranksHigherRawNearbyOnlyAggregate() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt",
                        line = 50,
                        text = listOf("Open queue"),
                        signals = listOf(
                            SourceSignal(
                                kind = SourceSignalKind.UI_TEXT,
                                value = "Open queue",
                                confidenceWeight = 1.0,
                            ),
                        ),
                    ),
                    SourceIndexEntry(
                        file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/model/FixThisDemoData.kt",
                        line = 122,
                        text = listOf("Diagnostics", "Review"),
                        signals = listOf(
                            SourceSignal(
                                kind = SourceSignalKind.UI_TEXT,
                                value = "Diagnostics",
                                confidenceWeight = 1.0,
                            ),
                            SourceSignal(
                                kind = SourceSignalKind.UI_TEXT,
                                value = "Review",
                                confidenceWeight = 1.0,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val matches = matcher.match(
            selectedNode = node(uid = "open-queue", text = listOf("Open queue")),
            nearbyNodes = listOf(
                node(uid = "diagnostics-tab", text = listOf("Diagnostics")),
                node(uid = "review-tab", text = listOf("Review")),
            ),
            activityName = null,
        )

        assertEquals(
            "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt",
            matches.first().file,
        )
        assertTrue(matches.first().matchReasons.contains("selected text"))
        assertTrue(matches[1].matchReasons.all { it.startsWith("nearby ") })
    }

    @Test
    fun runtimeLikeWeakLiteralTargetDoesNotBecomeHighConfidence() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "$SAMPLE_SOURCE_PREFIX/ui/ReplyListContent.kt",
                        line = 95,
                        text = listOf("Compose"),
                        signals = listOf(
                            SourceSignal(
                                kind = SourceSignalKind.ARBITRARY_STRING_LITERAL,
                                value = "Compose",
                                confidenceWeight = 1.0,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val top = matcher.match(
            selectedNode = node(uid = "compose-fab", text = listOf("Compose"), role = "Button"),
            nearbyNodes = emptyList(),
            activityName = "com.example.reply.ui.MainActivity",
        ).single()

        assertEquals(SelectionConfidence.LOW, top.confidence)
        assertTrue(top.riskFlags.contains(SourceCandidateRisk.ARBITRARY_LITERAL))
        assertFalse(top.evidenceStrength == SourceEvidenceStrength.STRONG)
    }

    @Test
    fun conventionTestTagLiteralAndComposableMatchesDoNotStackScore() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                        line = 12,
                        symbols = listOf("AppPrimaryButton"),
                        testTags = listOf("comp:AppPrimaryButton:primary"),
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(
                uid = "button",
                testTag = "comp:AppPrimaryButton:primary",
            ),
            nearbyNodes = emptyList(),
            activityName = null,
        ).single()

        assertEquals(SelectionConfidence.HIGH, match.confidence)
        assertEquals(1, match.ranking)
        assertEquals(null, match.scoreMargin) // single candidate — no runner-up, so scoreMargin is null
        assertEquals(SourceEvidenceStrength.STRONG, match.evidenceStrength)
        assertTrue(match.riskFlags.isEmpty())
        assertEquals(0.65, match.score, 0.0)
        assertTrue(match.matchedTerms.contains("comp:AppPrimaryButton:primary"))
        assertTrue(match.matchedTerms.contains("AppPrimaryButton"))
        assertTrue(match.matchReasons.contains("selected testTag"))
        assertTrue(match.matchReasons.contains("selected testTag convention composable"))
    }

    @Test
    fun directSelectedTestTagOutranksOwnerFunctionOnlyMatch() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/AdaptiveGridOwner.kt",
                        line = 38,
                        signals = listOf(
                            SourceSignal(SourceSignalKind.LAMBDA_OWNER_FUNCTION, "AdaptiveGrid"),
                        ),
                    ),
                    SourceIndexEntry(
                        file = "sample/src/main/java/AdaptiveGridTile.kt",
                        line = 24,
                        signals = listOf(
                            SourceSignal(SourceSignalKind.TEST_TAG, "comp:AdaptiveGrid:tile"),
                        ),
                    ),
                ),
            ),
        )

        val matches = matcher.match(
            selectedNode = node(uid = "tile", testTag = "comp:AdaptiveGrid:tile"),
            nearbyNodes = emptyList(),
            activityName = null,
        )

        assertEquals("sample/src/main/java/AdaptiveGridTile.kt", matches.first().file)
        assertTrue(matches.first().matchReasons.contains("selected testTag"))
        assertEquals("sample/src/main/java/AdaptiveGridOwner.kt", matches[1].file)
        assertTrue(matches[1].matchReasons.contains("selected owner composable"))
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
                                confidenceWeight = 0.35,
                            ),
                        ),
                    ),
                    SourceIndexEntry(
                        file = "CheckoutScreen.kt",
                        line = 12,
                        signals = listOf(
                            SourceSignal(
                                kind = SourceSignalKind.UI_TEXT,
                                value = "Pay now",
                                confidenceWeight = 1.0,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val matches = matcher.match(
            selectedNode = node(uid = "pay", text = listOf("Pay now")),
            nearbyNodes = emptyList(),
            activityName = null,
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
                        text = listOf("Pay now"),
                    ),
                ),
            ),
        )

        val matches = matcher.match(
            selectedNode = node(uid = "pay", text = listOf("Pay now")),
            nearbyNodes = emptyList(),
            activityName = null,
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
                                confidenceWeight = 1.0,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(uid = "pay", text = listOf("Pay now"), role = "Button"),
            nearbyNodes = emptyList(),
            activityName = "io.github.beyondwin.fixthis.sample.MainActivity",
        ).single()

        assertEquals("CheckoutScreen.kt", match.file)
        assertTrue(match.matchReasons.contains("selected text"))
        assertTrue(match.matchReasons.contains("selected role"))
        assertTrue(match.matchReasons.contains("activity"))
    }

    @Test
    fun ambiguousTopTwoMarginAttachesAmbiguousRiskAndDowngrades() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "ScreenA.kt",
                        line = 1,
                        text = listOf("Save"),
                        testTags = listOf("save"),
                    ),
                    SourceIndexEntry(
                        file = "ScreenB.kt",
                        line = 1,
                        text = listOf("Save"),
                        testTags = listOf("save"),
                    ),
                ),
            ),
        )

        val matches = matcher.match(
            selectedNode = node(uid = "save", text = listOf("Save"), testTag = "save"),
            nearbyNodes = emptyList(),
            activityName = null,
        )

        assertTrue(matches.size >= 2)
        val top = matches.first()
        assertTrue(top.confidence != SelectionConfidence.HIGH)
        assertTrue(SourceCandidateRisk.AMBIGUOUS in top.riskFlags)
    }

    @Test
    fun textOnlyMatchIsCappedAtMedium() {
        // Use a typed UI_TEXT signal so the entry matches via a typed signal (not legacy fallback).
        // An untyped fallback entry would produce UNTYPED_FALLBACK flag instead of TEXT_ONLY.
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "TextOnly.kt",
                        line = 1,
                        signals = listOf(
                            SourceSignal(
                                kind = SourceSignalKind.UI_TEXT,
                                value = "Hello",
                                confidenceWeight = 1.0,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(uid = "hello", text = listOf("Hello")),
            nearbyNodes = emptyList(),
            activityName = null,
        ).single()

        assertTrue(
            match.confidence == SelectionConfidence.MEDIUM || match.confidence == SelectionConfidence.LOW,
        )
        assertTrue(SourceCandidateRisk.TEXT_ONLY in match.riskFlags)
    }

    @Test
    fun nearbyOnlyMatchIsCappedAtLow() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "NearbyOnly.kt",
                        line = 1,
                        text = listOf("Pay"),
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(uid = "anchor"),
            nearbyNodes = listOf(node(uid = "pay-text", text = listOf("Pay"))),
            activityName = null,
        ).singleOrNull()

        if (match != null) {
            assertEquals(SelectionConfidence.LOW, match.confidence)
            assertTrue(SourceCandidateRisk.NEARBY_ONLY in match.riskFlags)
        }
    }

    @Test
    fun activityOnlyMatchIsCappedAtLow() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "ActivityOnly.kt",
                        line = 1,
                        activityNames = listOf("MainActivity"),
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(uid = "x"),
            nearbyNodes = emptyList(),
            activityName = "io.github.beyondwin.fixthis.sample.MainActivity",
        ).singleOrNull()

        if (match != null) {
            assertEquals(SelectionConfidence.LOW, match.confidence)
            assertTrue(SourceCandidateRisk.ACTIVITY_ONLY in match.riskFlags)
        }
    }

    @Test
    fun arbitraryLiteralOnlyMatchIsCappedAtLow() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "Literal.kt",
                        line = 1,
                        signals = listOf(
                            SourceSignal(
                                kind = SourceSignalKind.ARBITRARY_STRING_LITERAL,
                                value = "Pay now",
                                confidenceWeight = 0.35,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(uid = "pay", text = listOf("Pay now")),
            nearbyNodes = emptyList(),
            activityName = null,
        ).single()

        assertEquals(SelectionConfidence.LOW, match.confidence)
        assertTrue(SourceCandidateRisk.ARBITRARY_LITERAL in match.riskFlags)
        assertTrue("arbitrary literal" in match.matchReasons)
    }

    @Test
    fun untypedFallbackMatchEmitsLegacyWireReasonAndCapsAtLow() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "LegacyOnly.kt",
                        line = 1,
                        text = listOf("Pay now"),
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(uid = "pay", text = listOf("Pay now")),
            nearbyNodes = emptyList(),
            activityName = null,
        ).single()

        // The fixture has no typed signals, so the untyped fallback origin marker
        // fires (emitted with the "legacy fallback" wire reason for compatibility)
        // and dominates over the text-only cap (untyped fallback is more specific /
        // stronger evidence about candidate quality).
        assertEquals(SelectionConfidence.LOW, match.confidence)
        assertTrue(SourceCandidateRisk.UNTYPED_FALLBACK in match.riskFlags)
        assertTrue("legacy fallback" in match.matchReasons)
    }

    @Test
    fun strongEvidenceWithClearMarginRemainsHigh() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "AppPrimaryButton.kt",
                        line = 12,
                        symbols = listOf("AppPrimaryButton"),
                        testTags = listOf("comp:AppPrimaryButton:primary"),
                    ),
                    SourceIndexEntry(
                        file = "Other.kt",
                        line = 1,
                        text = listOf("primary"),
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(uid = "btn", testTag = "comp:AppPrimaryButton:primary"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).first()

        assertEquals(SelectionConfidence.HIGH, match.confidence)
        // Other.kt scores 0 (no matching signals) so only AppPrimaryButton.kt is returned;
        // with a single candidate, scoreMargin is null (no runner-up to compute difference against).
        assertEquals(null, match.scoreMargin)
    }

    @Test
    fun scoreMarginIsPopulatedOnRankOneWhenRunnerUpExists() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "HighScore.kt",
                        line = 1,
                        signals = listOf(
                            SourceSignal(
                                kind = SourceSignalKind.UI_TEXT,
                                value = "Checkout",
                                confidenceWeight = 1.0,
                            ),
                        ),
                    ),
                    SourceIndexEntry(
                        file = "LowScore.kt",
                        line = 1,
                        signals = listOf(
                            SourceSignal(
                                kind = SourceSignalKind.ARBITRARY_STRING_LITERAL,
                                value = "Checkout",
                                confidenceWeight = 0.35,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val results = matcher.match(
            selectedNode = node(uid = "checkout", text = listOf("Checkout")),
            nearbyNodes = emptyList(),
            activityName = null,
        )

        assertTrue("Expected ≥2 candidates", results.size >= 2)
        assertFalse("rank-1 scoreMargin must not be null", results[0].scoreMargin == null)
        assertEquals(
            "scoreMargin must equal score[0] - score[1]",
            results[0].score - results[1].score,
            results[0].scoreMargin!!,
            1e-6,
        )
    }

    @Test
    fun scoreMarginIsNullWhenOnlyOneCandidate() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "OnlyOne.kt",
                        line = 1,
                        signals = listOf(
                            SourceSignal(
                                kind = SourceSignalKind.UI_TEXT,
                                value = "Submit",
                                confidenceWeight = 1.0,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val results = matcher.match(
            selectedNode = node(uid = "submit", text = listOf("Submit")),
            nearbyNodes = emptyList(),
            activityName = null,
        )

        assertEquals("Expected exactly 1 candidate", 1, results.size)
        assertEquals("scoreMargin must be null when only one candidate", null, results[0].scoreMargin)
    }

    @Test
    fun matchReturnsEmptyListWhenNoCandidates() {
        val matcher = SourceMatcher(
            SourceIndex(entries = emptyList()),
        )

        val results = matcher.match(
            selectedNode = node(uid = "btn", text = listOf("Any Text")),
            nearbyNodes = emptyList(),
            activityName = null,
        )

        assertTrue("Expected empty result list when no index entries", results.isEmpty())
    }

    @Test
    fun selectsKotlinCallSiteOverStringsXmlWhenOnlyResolvedTextMatches() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "src/main/kotlin/com/example/LoginScreen.kt",
                    line = 42,
                    stringResources = listOf("login_button"),
                    signals = listOf(
                        SourceSignal(SourceSignalKind.STRING_RESOURCE, "login_button"),
                        SourceSignal(SourceSignalKind.STRING_RESOURCE_RESOLVED, "로그인"),
                    ),
                ),
                SourceIndexEntry(
                    file = "src/main/res/values/strings.xml",
                    line = 5,
                    text = listOf("로그인"),
                    stringResources = listOf("login_button"),
                    signals = listOf(
                        SourceSignal(SourceSignalKind.UI_TEXT, "로그인"),
                        SourceSignal(SourceSignalKind.STRING_RESOURCE, "login_button"),
                    ),
                ),
            ),
        )

        val candidates = SourceMatcher(index).match(
            selectedNode = node(uid = "login-button", text = listOf("로그인")),
            nearbyNodes = emptyList(),
            activityName = null,
        )

        assertEquals("src/main/kotlin/com/example/LoginScreen.kt", candidates.first().file)
        assertTrue(candidates.first().matchReasons.contains("selected resolved stringResource"))
    }

    @Test
    fun sourceCandidateCarriesEnclosingComposableOwner() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "sample/src/main/kotlin/LoginScreen.kt",
                    line = 42,
                    signals = listOf(
                        SourceSignal(SourceSignalKind.UI_TEXT, "로그인"),
                        SourceSignal(SourceSignalKind.LAMBDA_OWNER_FUNCTION, "LoginScreen"),
                    ),
                ),
            ),
        )

        val candidates = SourceMatcher(index).match(
            selectedNode = node(uid = "login-button", text = listOf("로그인")),
            nearbyNodes = emptyList(),
            activityName = null,
        )

        assertEquals("LoginScreen", candidates.single().ownerComposable)
    }

    @Test
    fun selectedStateDescriptionCanMatchResolvedStringResource() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "feature/catalog/src/main/kotlin/CatalogCards.kt",
                    line = 230,
                    signals = listOf(
                        SourceSignal(SourceSignalKind.STRING_RESOURCE, "catalog_item_saved"),
                        SourceSignal(SourceSignalKind.STRING_RESOURCE_RESOLVED, "저장됨"),
                    ),
                ),
            ),
        )

        val candidates = SourceMatcher(index).match(
            selectedNode = node(uid = "save-toggle").copy(stateDescription = "저장됨"),
            nearbyNodes = emptyList(),
            activityName = null,
        )

        assertEquals("feature/catalog/src/main/kotlin/CatalogCards.kt", candidates.single().file)
        assertEquals(SelectionConfidence.MEDIUM, candidates.single().confidence)
        assertTrue(candidates.single().matchReasons.contains("selected stateDescription"))
    }

    @Test
    fun nearbyOnlyAndActivityOnlyMatchesStayLowConfidence() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/DiagnosticsScreen.kt",
                        line = 20,
                        text = listOf("Retry"),
                        activityNames = listOf("MainActivity"),
                        signals = listOf(
                            SourceSignal(SourceSignalKind.UI_TEXT, "Retry"),
                            SourceSignal(SourceSignalKind.ACTIVITY_NAME, "MainActivity"),
                        ),
                    ),
                ),
            ),
        )

        val nearbyOnly = matcher.match(
            selectedNode = node(uid = "empty-target"),
            nearbyNodes = listOf(node(uid = "retry-label", text = listOf("Retry"))),
            activityName = null,
        ).single()

        val activityOnly = matcher.match(
            selectedNode = node(uid = "empty-target"),
            nearbyNodes = emptyList(),
            activityName = "io.github.beyondwin.fixthis.sample.MainActivity",
        ).single()

        assertEquals(SelectionConfidence.LOW, nearbyOnly.confidence)
        assertTrue(nearbyOnly.riskFlags.contains(SourceCandidateRisk.NEARBY_ONLY))
        assertEquals(SelectionConfidence.LOW, activityOnly.confidence)
        assertTrue(activityOnly.riskFlags.contains(SourceCandidateRisk.ACTIVITY_ONLY))
    }

    @Test
    fun ownerFunctionMatchWithoutDirectUiEvidenceDoesNotBecomeHighConfidence() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/AdaptiveGrid.kt",
                        line = 38,
                        signals = listOf(
                            SourceSignal(SourceSignalKind.LAMBDA_OWNER_FUNCTION, "AdaptiveGrid"),
                        ),
                    ),
                    SourceIndexEntry(
                        file = "sample/src/main/java/AdaptiveGrid.kt",
                        line = 12,
                        symbols = listOf("AdaptiveGrid"),
                        signals = listOf(
                            SourceSignal(SourceSignalKind.COMPOSABLE_SYMBOL, "AdaptiveGrid", confidenceWeight = 0.7),
                        ),
                    ),
                ),
            ),
        )

        val matches = matcher.match(
            selectedNode = node(uid = "tile", testTag = "comp:AdaptiveGrid:tile"),
            nearbyNodes = emptyList(),
            activityName = null,
        )

        assertEquals("sample/src/main/java/AdaptiveGrid.kt", matches.first().file)
        assertEquals(38, matches.first().line)
        assertEquals(SelectionConfidence.MEDIUM, matches.first().confidence)
        assertTrue(matches.first().matchReasons.contains("selected owner composable"))
    }

    @Test
    fun lazyItemOwnerMatchSurfacesItemComposableAsSelectedOwner() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/OrderList.kt",
                        line = 9,
                        signals = listOf(
                            SourceSignal(SourceSignalKind.LAZY_ITEM_OWNER, "OrderRow"),
                        ),
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(uid = "row", testTag = "comp:OrderRow:row"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).single()

        assertEquals("sample/src/main/java/OrderList.kt", match.file)
        assertTrue(match.matchReasons.contains("selected owner composable"))
    }

    @Test
    fun ambiguousOwnerFunctionMatchKeepsMediumConfidenceWithRiskFlag() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/AdaptiveGridPrimary.kt",
                        line = 38,
                        signals = listOf(
                            SourceSignal(SourceSignalKind.LAMBDA_OWNER_FUNCTION, "AdaptiveGrid"),
                        ),
                    ),
                    SourceIndexEntry(
                        file = "sample/src/main/java/AdaptiveGridAlternate.kt",
                        line = 42,
                        signals = listOf(
                            SourceSignal(
                                SourceSignalKind.LAMBDA_OWNER_FUNCTION,
                                "AdaptiveGrid",
                                confidenceWeight = 0.95,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(uid = "tile", testTag = "comp:AdaptiveGrid:tile"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).first()

        assertEquals("sample/src/main/java/AdaptiveGridPrimary.kt", match.file)
        assertEquals(SelectionConfidence.MEDIUM, match.confidence)
        assertTrue(SourceCandidateRisk.AMBIGUOUS in match.riskFlags)
        assertTrue(match.matchReasons.contains("selected owner composable"))
    }

    @Test
    fun ownerFunctionPlusArbitraryLiteralDoesNotBecomeHighConfidence() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/AdaptiveGrid.kt",
                        line = 38,
                        signals = listOf(
                            SourceSignal(SourceSignalKind.ARBITRARY_STRING_LITERAL, "Pay now", confidenceWeight = 0.35),
                            SourceSignal(SourceSignalKind.LAMBDA_OWNER_FUNCTION, "AdaptiveGrid"),
                        ),
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(uid = "tile", text = listOf("Pay now"), testTag = "comp:AdaptiveGrid:tile"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).single()

        assertEquals(SelectionConfidence.MEDIUM, match.confidence)
        assertTrue(SourceCandidateRisk.ARBITRARY_LITERAL in match.riskFlags)
        assertTrue(match.matchReasons.contains("selected owner composable"))
        assertTrue(match.matchReasons.contains("arbitrary literal"))
    }

    @Test
    fun strictComposableTagCanSurfaceLayoutRendererEntryAsMediumConfidence() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/AdaptiveGrid.kt",
                        line = 38,
                        signals = listOf(
                            SourceSignal(SourceSignalKind.LAYOUT_RENDERER, "Layout"),
                            SourceSignal(SourceSignalKind.LAMBDA_OWNER_FUNCTION, "AdaptiveGrid"),
                        ),
                        excerpt = "Layout(content = {}, measurePolicy = { ... })",
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(uid = "tile", testTag = "comp:AdaptiveGrid:tile"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).single()

        assertEquals("sample/src/main/java/AdaptiveGrid.kt", match.file)
        assertEquals(38, match.line)
        assertEquals(SelectionConfidence.MEDIUM, match.confidence)
        assertTrue(match.matchReasons.contains("selected owner composable"))
        assertTrue(match.matchReasons.contains("layout renderer context"))
        assertEquals(SourceEvidenceStrength.MEDIUM, match.evidenceStrength)
        assertFalse(SourceCandidateRisk.ARBITRARY_LITERAL in match.riskFlags)
        assertEquals("AdaptiveGrid", match.ownerComposable)
    }

    @Test
    fun strictComposableTagPrefersLayoutRendererCallSiteOverOwnerOnlyEntry() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/AdaptiveGrid.kt",
                        line = 12,
                        signals = listOf(
                            SourceSignal(SourceSignalKind.LAMBDA_OWNER_FUNCTION, "AdaptiveGrid"),
                        ),
                        excerpt = "val spacing = 8.dp",
                    ),
                    SourceIndexEntry(
                        file = "sample/src/main/java/AdaptiveGrid.kt",
                        line = 38,
                        signals = listOf(
                            SourceSignal(SourceSignalKind.LAYOUT_RENDERER, "Layout"),
                            SourceSignal(SourceSignalKind.LAMBDA_OWNER_FUNCTION, "AdaptiveGrid"),
                        ),
                        excerpt = "Layout(content = {}, measurePolicy = { ... })",
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(uid = "tile", testTag = "comp:AdaptiveGrid:tile"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).first()

        assertEquals("sample/src/main/java/AdaptiveGrid.kt", match.file)
        assertEquals(38, match.line)
        assertEquals(SelectionConfidence.MEDIUM, match.confidence)
        assertTrue(match.matchReasons.contains("selected owner composable"))
        assertTrue(match.matchReasons.contains("layout renderer context"))
    }

    @Test
    fun emitsSharedComponentDefinitionReasonWhenDefinitionMatchesAndSignalPresent() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "$SAMPLE_SOURCE_PREFIX/components/PrimaryButton.kt",
                    line = 8,
                    symbols = listOf("PrimaryButton"),
                    testTags = listOf("comp:PrimaryButton:root"),
                    signals = listOf(
                        SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                        SourceSignal(kind = SourceSignalKind.STRICT_COMP_TEST_TAG, value = "comp:PrimaryButton:root"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "4"),
                    ),
                    excerpt = "@Composable fun PrimaryButton(",
                ),
            ),
        )

        val matches = SourceMatcher(index).match(
            selectedNode = node(uid = "primary", testTag = "comp:PrimaryButton:root"),
            nearbyNodes = emptyList(),
            activityName = null,
        )

        assertTrue(matches.first().matchReasons.contains("shared component definition"))
    }

    @Test
    fun doesNotEmitSharedComponentReasonForTextOnlyMatchInReusedFile() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "$SAMPLE_SOURCE_PREFIX/components/PrimaryButton.kt",
                    line = 8,
                    symbols = listOf("PrimaryButton"),
                    text = listOf("Save changes"),
                    signals = listOf(
                        SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                        SourceSignal(kind = SourceSignalKind.UI_TEXT, value = "Save changes"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "4"),
                    ),
                    excerpt = "Text(\"Save changes\")",
                ),
            ),
        )

        val matches = SourceMatcher(index).match(
            selectedNode = node(uid = "save", text = listOf("Save changes")),
            nearbyNodes = emptyList(),
            activityName = null,
        )

        assertFalse(matches.first().matchReasons.contains("shared component definition"))
    }

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        contentDescription: List<String> = emptyList(),
        role: String? = null,
        testTag: String? = null,
        actions: List<String> = emptyList(),
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
        actions = actions,
    )

    @Test
    fun capsSharedComponentDefinitionAtMediumWithFlagAndCaution() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "$SAMPLE_SOURCE_PREFIX/components/PrimaryButton.kt",
                    line = 8,
                    symbols = listOf("PrimaryButton"),
                    testTags = listOf("comp:PrimaryButton:root"),
                    signals = listOf(
                        SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                        SourceSignal(kind = SourceSignalKind.STRICT_COMP_TEST_TAG, value = "comp:PrimaryButton:root"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "5"),
                    ),
                    excerpt = "@Composable fun PrimaryButton(",
                ),
            ),
        )

        val candidate = SourceMatcher(index).match(
            selectedNode = node(uid = "primary", testTag = "comp:PrimaryButton:root"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).first()

        assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
        assertTrue(candidate.riskFlags.contains(SourceCandidateRisk.SHARED_COMPONENT))
        assertEquals(
            "Shared component definition (used in multiple places); editing it changes every usage. Verify the specific call site before editing.",
            candidate.caution,
        )
    }

    @Test
    fun singleUseDefinitionWithSameEvidenceStaysHigh() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "$SAMPLE_SOURCE_PREFIX/components/PrimaryButton.kt",
                    line = 8,
                    symbols = listOf("PrimaryButton"),
                    testTags = listOf("comp:PrimaryButton:root"),
                    signals = listOf(
                        SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                        SourceSignal(kind = SourceSignalKind.STRICT_COMP_TEST_TAG, value = "comp:PrimaryButton:root"),
                    ),
                    excerpt = "@Composable fun PrimaryButton(",
                ),
            ),
        )

        val candidate = SourceMatcher(index).match(
            selectedNode = node(uid = "primary", testTag = "comp:PrimaryButton:root"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).first()

        assertEquals(SelectionConfidence.HIGH, candidate.confidence)
        assertFalse(candidate.riskFlags.contains(SourceCandidateRisk.SHARED_COMPONENT))
    }

    @Test
    fun populatesCallSitesForSharedComponentDefinition() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "$SAMPLE_SOURCE_PREFIX/components/PrimaryButton.kt",
                    line = 8,
                    symbols = listOf("PrimaryButton"),
                    testTags = listOf("comp:PrimaryButton:root"),
                    signals = listOf(
                        SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                        SourceSignal(kind = SourceSignalKind.STRICT_COMP_TEST_TAG, value = "comp:PrimaryButton:root"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "2"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE, value = "ui/ScreenA.kt:42"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE, value = "ui/ScreenB.kt:13"),
                    ),
                    excerpt = "@Composable fun PrimaryButton(",
                ),
            ),
        )

        val candidate = SourceMatcher(index).match(
            selectedNode = node(uid = "primary", testTag = "comp:PrimaryButton:root"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).first()

        assertEquals(
            listOf(
                SourceLocationRef(file = "ui/ScreenA.kt", line = 42),
                SourceLocationRef(file = "ui/ScreenB.kt", line = 13),
            ),
            candidate.callSites,
        )
    }

    @Test
    fun ranksSharedComponentCallSitesBySelectionEvidenceAndKeepsMediumConfidence() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "$SAMPLE_SOURCE_PREFIX/components/PrimaryButton.kt",
                    line = 8,
                    symbols = listOf("PrimaryButton"),
                    testTags = listOf("comp:PrimaryButton:root"),
                    signals = listOf(
                        SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                        SourceSignal(kind = SourceSignalKind.STRICT_COMP_TEST_TAG, value = "comp:PrimaryButton:root"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "2"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE, value = "ui/ScreenA.kt:42\tScreenA\tCancel"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE, value = "ui/ScreenB.kt:13\tScreenB\tSave changes"),
                    ),
                    excerpt = "@Composable fun PrimaryButton(",
                ),
            ),
        )

        val candidate = SourceMatcher(index).match(
            selectedNode = node(uid = "primary", text = listOf("Save changes"), testTag = "comp:PrimaryButton:root"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).first()

        assertEquals(
            listOf(
                SourceLocationRef(file = "ui/ScreenB.kt", line = 13, mostLikely = true, recommendedEditSite = true),
                SourceLocationRef(file = "ui/ScreenA.kt", line = 42, mostLikely = false),
            ),
            candidate.callSites,
        )
        assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
        assertTrue(candidate.riskFlags.contains(SourceCandidateRisk.SHARED_COMPONENT))
    }

    @Test
    fun capsFanInSharedComponentBodyTestTagMatchAtMedium() {
        // Reused composable StudioHeader (fan-in = 4). The definition entry carries the
        // SHARED_COMPONENT signal; a SEPARATE body entry within the same ownerComposable
        // matches the exact strict testTag and clearly outranks the definition, so it
        // escapes the per-candidate SHARED_COMPONENT cap and would resolve at HIGH.
        // The body entry must inherit the MEDIUM cap from its shared owner instead, so the
        // overall top candidate must never resolve at HIGH.
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "$SAMPLE_SOURCE_PREFIX/components/StudioHeader.kt",
                    line = 29,
                    text = listOf("Studio overview"),
                    testTags = listOf("comp:StudioHeader:root"),
                    signals = listOf(
                        SourceSignal(kind = SourceSignalKind.STRICT_COMP_TEST_TAG, value = "comp:StudioHeader:root"),
                        SourceSignal(kind = SourceSignalKind.UI_TEXT, value = "Studio overview"),
                        SourceSignal(kind = SourceSignalKind.LAMBDA_OWNER_FUNCTION, value = "StudioHeader"),
                    ),
                    excerpt = ".testTag(\"comp:StudioHeader:root\")",
                ),
                SourceIndexEntry(
                    file = "$SAMPLE_SOURCE_PREFIX/components/StudioHeader.kt",
                    line = 21,
                    symbols = listOf("StudioHeader"),
                    signals = listOf(
                        SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "StudioHeader"),
                        SourceSignal(kind = SourceSignalKind.LAMBDA_OWNER_FUNCTION, value = "StudioHeader"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "4"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE, value = "screens/HomeScreen.kt:44"),
                    ),
                    excerpt = "@Composable fun StudioHeader(",
                ),
            ),
        )

        val candidate = SourceMatcher(index).match(
            selectedNode = node(uid = "header", text = listOf("Studio overview"), testTag = "comp:StudioHeader:root"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).first()

        // The body/testTag entry (line 29) clearly outranks the definition (line 21), so
        // without the fan-in cap it resolves at HIGH despite belonging to a shared owner.
        assertEquals(29, candidate.line)
        assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
        assertTrue(candidate.riskFlags.contains(SourceCandidateRisk.SHARED_COMPONENT))
    }

    @Test
    fun leavesCallSitesEmptyForNonSharedComponentMatch() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "$SAMPLE_SOURCE_PREFIX/components/PrimaryButton.kt",
                    line = 8,
                    symbols = listOf("PrimaryButton"),
                    text = listOf("Save changes"),
                    signals = listOf(
                        SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                        SourceSignal(kind = SourceSignalKind.UI_TEXT, value = "Save changes"),
                    ),
                    excerpt = "Text(\"Save changes\")",
                ),
            ),
        )

        val candidate = SourceMatcher(index).match(
            selectedNode = node(uid = "save", text = listOf("Save changes")),
            nearbyNodes = emptyList(),
            activityName = null,
        ).first()

        assertEquals(emptyList<SourceLocationRef>(), candidate.callSites)
    }

    @Test
    fun slotWrapperLayoutRendererSurfacesAsMediumConfidence() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/CardSlot.kt",
                        line = 12,
                        signals = listOf(
                            SourceSignal(SourceSignalKind.LAYOUT_RENDERER, "CardSlot"),
                            SourceSignal(SourceSignalKind.LAMBDA_OWNER_FUNCTION, "CardSlot"),
                        ),
                        excerpt = "fun CardSlot(content: @Composable () -> Unit) {",
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(uid = "body", testTag = "comp:CardSlot:body"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).single()

        assertEquals("sample/src/main/java/CardSlot.kt", match.file)
        assertEquals(SelectionConfidence.MEDIUM, match.confidence)
        assertTrue(match.matchReasons.contains("layout renderer context"))
        assertEquals("CardSlot", match.ownerComposable)
    }
}
