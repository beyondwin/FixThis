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
 * End-to-end exercise of the shared-component confidence cap through the real
 * [SourceMatcher.match] pipeline (not just [SourceRiskClassifier] in isolation).
 *
 * The confident fixture must still expose exactly one `recommendedEditSite`,
 * but the shared definition candidate remains MEDIUM because the edit site is
 * verification context, not exact ownership.
 *
 * Call-site signal value format (confirmed against `parseCallSiteSignal` /
 * `rankSharedComponentCallSites`): `file:line<TAB>enclosingFunction<TAB>literal|literal`.
 */
private const val COMP_SOURCE_PREFIX = "sample/src/main/java/io/github/beyondwin/fixthis/sample"

class SourceMatcherSharedComponentTest {
    @Test
    fun confidentCallSiteKeepsRecommendedEditSiteButDefinitionStaysMedium() {
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

        assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
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
