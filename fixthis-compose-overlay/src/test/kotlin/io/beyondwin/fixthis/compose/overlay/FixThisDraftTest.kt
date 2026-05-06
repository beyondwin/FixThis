package io.beyondwin.fixthis.compose.overlay

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.ScopeCandidate
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SelectionInfo
import io.beyondwin.fixthis.compose.core.model.SelectionKind
import io.beyondwin.fixthis.compose.core.model.SelectionSource
import io.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixThisDraftTest {
    @Test
    fun selectedBoundsPreferSelectedNodeBoundsOverAreaSelection() {
        val selectedNode = fixThisNode(
            uid = "button",
            bounds = FixThisRect(left = 8f, top = 16f, right = 108f, bottom = 72f),
        )
        val areaBounds = FixThisRect(left = 0f, top = 0f, right = 160f, bottom = 120f)

        val draft = FixThisDraft(
            selectedNode = selectedNode,
            selection = SelectionInfo(
                kind = SelectionKind.VISUAL_AREA,
                confidence = SelectionConfidence.MEDIUM,
                areaBoundsInWindow = areaBounds,
                source = SelectionSource.AREA_SELECT,
            ),
        )

        assertEquals(selectedNode.boundsInWindow, draft.selectedBounds)
    }

    @Test
    fun selectedBoundsFallBackToAreaSelectionWhenNoNodeIsSelected() {
        val areaBounds = FixThisRect(left = 0f, top = 0f, right = 160f, bottom = 120f)

        val draft = FixThisDraft(
            selection = SelectionInfo(
                kind = SelectionKind.VISUAL_AREA,
                confidence = SelectionConfidence.MEDIUM,
                areaBoundsInWindow = areaBounds,
                source = SelectionSource.AREA_SELECT,
            ),
        )

        assertEquals(areaBounds, draft.selectedBounds)
    }

    @Test
    fun selectedSummaryIncludesNodeTextAndScopeCount() {
        val draft = FixThisDraft(
            selectedNode = fixThisNode(
                uid = "checkout",
                bounds = FixThisRect(left = 8f, top = 16f, right = 108f, bottom = 72f),
                text = listOf("Checkout"),
                role = "Button",
            ),
            scopeCandidates = listOf(
                ScopeCandidate(
                    label = "Checkout row",
                    nodeUid = "row",
                    boundsInWindow = FixThisRect(left = 0f, top = 0f, right = 180f, bottom = 96f),
                    score = 0.84,
                ),
            ),
        )

        assertTrue(draft.selectedSummary.contains("Checkout"))
        assertTrue(draft.selectedSummary.contains("Button"))
        assertTrue(draft.selectedSummary.contains("1 scope"))
    }
}

private fun fixThisNode(
    uid: String,
    bounds: FixThisRect,
    text: List<String> = emptyList(),
    role: String? = null,
): FixThisNode =
    FixThisNode(
        uid = uid,
        composeNodeId = 1,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
        role = role,
    )
