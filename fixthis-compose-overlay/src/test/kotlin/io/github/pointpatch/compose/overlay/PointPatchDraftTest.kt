package io.github.pointpatch.compose.overlay

import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.ScopeCandidate
import io.github.pointpatch.compose.core.model.SelectionConfidence
import io.github.pointpatch.compose.core.model.SelectionInfo
import io.github.pointpatch.compose.core.model.SelectionKind
import io.github.pointpatch.compose.core.model.SelectionSource
import io.github.pointpatch.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointPatchDraftTest {
    @Test
    fun selectedBoundsPreferSelectedNodeBoundsOverAreaSelection() {
        val selectedNode = pointPatchNode(
            uid = "button",
            bounds = PointPatchRect(left = 8f, top = 16f, right = 108f, bottom = 72f),
        )
        val areaBounds = PointPatchRect(left = 0f, top = 0f, right = 160f, bottom = 120f)

        val draft = PointPatchDraft(
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
        val areaBounds = PointPatchRect(left = 0f, top = 0f, right = 160f, bottom = 120f)

        val draft = PointPatchDraft(
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
        val draft = PointPatchDraft(
            selectedNode = pointPatchNode(
                uid = "checkout",
                bounds = PointPatchRect(left = 8f, top = 16f, right = 108f, bottom = 72f),
                text = listOf("Checkout"),
                role = "Button",
            ),
            scopeCandidates = listOf(
                ScopeCandidate(
                    label = "Checkout row",
                    nodeUid = "row",
                    boundsInWindow = PointPatchRect(left = 0f, top = 0f, right = 180f, bottom = 96f),
                    score = 0.84,
                ),
            ),
        )

        assertTrue(draft.selectedSummary.contains("Checkout"))
        assertTrue(draft.selectedSummary.contains("Button"))
        assertTrue(draft.selectedSummary.contains("1 scope"))
    }
}

private fun pointPatchNode(
    uid: String,
    bounds: PointPatchRect,
    text: List<String> = emptyList(),
    role: String? = null,
): PointPatchNode =
    PointPatchNode(
        uid = uid,
        composeNodeId = 1,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
        role = role,
    )
