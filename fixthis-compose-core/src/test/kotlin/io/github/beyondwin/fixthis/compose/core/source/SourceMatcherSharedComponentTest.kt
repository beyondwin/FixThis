package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end exercise of the K2 shared-component confidence relaxation through the real
 * [SourceMatcher.match] pipeline (not just [SourceRiskClassifier] in isolation).
 *
 * Both fixtures share an identical shared-component definition (same strict-comp testTag,
 * same fan-in) selected via the same strict-comp testTag, so the ONLY difference is whether the
 * call-site ranking yields exactly one confident `recommendedEditSite`:
 *  - [confidentCallSiteYieldsHigh]: one call site's literal strongly overlaps the selection tokens
 *    and clears the next site by the margin, so `confidentCallSite` is true and HIGH is preserved.
 *  - [ambiguousCallSitesStayMedium]: both call sites carry identical, non-overlapping context so no
 *    site is marked, `confidentCallSite` is false, and the SHARED_COMPONENT cap forces MEDIUM.
 *
 * Call-site signal value format (confirmed against `parseCallSiteSignal` /
 * `rankSharedComponentCallSites`): `file:line<TAB>enclosingFunction<TAB>literal|literal`.
 */
private const val COMP_SOURCE_PREFIX = "sample/src/main/java/io/github/beyondwin/fixthis/sample"

class SourceMatcherSharedComponentTest {
    @Test
    fun confidentCallSiteYieldsHigh() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "$COMP_SOURCE_PREFIX/components/PrimaryButton.kt",
                    line = 8,
                    symbols = listOf("PrimaryButton"),
                    testTags = listOf("comp:PrimaryButton:root"),
                    signals = listOf(
                        SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                        SourceSignal(kind = SourceSignalKind.STRICT_COMP_TEST_TAG, value = "comp:PrimaryButton:root"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "2"),
                        // Confident: this site's literal "Checkout" overlaps the selection token.
                        SourceSignal(
                            kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE,
                            value = "app/CartScreen.kt:64\tCartScreen\tCheckout",
                        ),
                        // Decoy: neither enclosing nor literal overlaps the selection token.
                        SourceSignal(
                            kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE,
                            value = "app/HomeScreen.kt:20\tHomeScreen\tDismiss",
                        ),
                    ),
                    excerpt = "@Composable fun PrimaryButton(",
                ),
            ),
        )

        val candidate = SourceMatcher(index).match(
            selectedNode = node(uid = "checkout", text = listOf("Checkout"), testTag = "comp:PrimaryButton:checkout"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).first()

        // K2: exactly one confident recommended edit site + a strict-comp testTag relaxes the
        // shared-component cap, so HIGH survives while SHARED_COMPONENT is still surfaced.
        assertEquals(SelectionConfidence.HIGH, candidate.confidence)
        assertTrue(candidate.riskFlags.contains(SourceCandidateRisk.SHARED_COMPONENT))
        assertEquals(1, candidate.callSites.count { it.recommendedEditSite })
    }

    @Test
    fun ambiguousCallSitesStayMedium() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "$COMP_SOURCE_PREFIX/components/PrimaryButton.kt",
                    line = 8,
                    symbols = listOf("PrimaryButton"),
                    testTags = listOf("comp:PrimaryButton:root"),
                    signals = listOf(
                        SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                        SourceSignal(kind = SourceSignalKind.STRICT_COMP_TEST_TAG, value = "comp:PrimaryButton:root"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "2"),
                        // Ambiguous: both sites carry identical, non-overlapping context so neither
                        // outranks the other -> no recommendedEditSite is marked.
                        SourceSignal(
                            kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE,
                            value = "app/CartScreen.kt:64\tShared\tShared",
                        ),
                        SourceSignal(
                            kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE,
                            value = "app/HomeScreen.kt:20\tShared\tShared",
                        ),
                    ),
                    excerpt = "@Composable fun PrimaryButton(",
                ),
            ),
        )

        val candidate = SourceMatcher(index).match(
            selectedNode = node(uid = "checkout", text = listOf("Checkout"), testTag = "comp:PrimaryButton:checkout"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).first()

        // No single confident call site -> confidentCallSite is false -> SHARED_COMPONENT cap holds.
        assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
        assertTrue(candidate.riskFlags.contains(SourceCandidateRisk.SHARED_COMPONENT))
        assertEquals(0, candidate.callSites.count { it.recommendedEditSite })
    }

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        contentDescription: List<String> = emptyList(),
        role: String? = null,
        testTag: String? = null,
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
    )
}
