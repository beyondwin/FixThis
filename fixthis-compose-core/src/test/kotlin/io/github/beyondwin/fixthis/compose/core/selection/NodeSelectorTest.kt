package io.github.beyondwin.fixthis.compose.core.selection

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SelectionKind
import io.github.beyondwin.fixthis.compose.core.model.SelectionSource
import io.github.beyondwin.fixthis.compose.core.model.TapPoint
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeSelectorTest {
    @Test
    fun selectsClickableButtonOverTextAtSameTapPoint() {
        val root = node(
            uid = "root",
            bounds = rect(0f, 0f, 400f, 800f),
        )
        val button = node(
            uid = "pay-button",
            bounds = rect(40f, 100f, 220f, 156f),
            text = listOf("Pay now"),
            role = "Button",
            actions = listOf("OnClick"),
        )
        val text = node(
            uid = "pay-text",
            bounds = rect(70f, 112f, 180f, 140f),
            text = listOf("Pay now"),
        )

        val result = NodeSelector.select(
            nodes = listOf(root, button, text),
            tap = TapPoint(110f, 128f),
        )

        assertEquals("pay-button", result.selectedNode?.uid)
        assertEquals(SelectionKind.SEMANTICS_NODE, result.selection.kind)
        assertEquals(SelectionConfidence.HIGH, result.selection.confidence)
        assertEquals(SelectionSource.TAP_SELECT, result.selection.source)
        assertEquals("pay-button", result.selection.selectedUid)
        assertEquals("pay-button", result.candidatesAtPoint.first().node.uid)
        assertTrue(result.candidatesAtPoint.first().breakdown.getValue("action") > 0.0)
    }

    @Test
    fun appliesRootAndHugeContainerPenalty() {
        val root = node(
            uid = "root",
            bounds = rect(0f, 0f, 1080f, 2400f),
        )
        val card = node(
            uid = "empty-card",
            bounds = rect(20f, 80f, 1000f, 900f),
        )
        val button = node(
            uid = "details-button",
            bounds = rect(80f, 160f, 260f, 220f),
            text = listOf("Details"),
            role = "Button",
            actions = listOf("OnClick"),
        )

        val result = NodeSelector.select(
            nodes = listOf(root, card, button),
            tap = TapPoint(100f, 180f),
        )

        assertEquals("details-button", result.selectedNode?.uid)
        assertTrue(result.candidatesAtPoint.map { it.node.uid }.containsAll(listOf("root", "empty-card", "details-button")))
        assertTrue(result.candidatesAtPoint.first { it.node.uid == "root" }.breakdown.getValue("rootPenalty") < 0.0)
        assertTrue(result.candidatesAtPoint.first { it.node.uid == "empty-card" }.breakdown.getValue("largeEmptyPenalty") < 0.0)
    }

    @Test
    fun returnsTapPointFallbackWhenNoNodeContainsTap() {
        val result = NodeSelector.select(
            nodes = listOf(
                node(
                    uid = "outside",
                    bounds = rect(0f, 0f, 100f, 100f),
                    text = listOf("Outside"),
                ),
            ),
            tap = TapPoint(300f, 300f),
        )

        assertNull(result.selectedNode)
        assertTrue(result.candidatesAtPoint.isEmpty())
        assertTrue(result.scopeCandidates.isEmpty())
        assertEquals(SelectionKind.TAP_POINT, result.selection.kind)
        assertEquals(SelectionConfidence.NONE, result.selection.confidence)
        assertEquals(SelectionSource.FALLBACK, result.selection.source)
        assertNull(result.selection.selectedUid)
    }

    @Test
    fun stillSelectsBestNodeWhenMaxCandidatesIsZero() {
        val button = node(
            uid = "save-button",
            bounds = rect(20f, 20f, 140f, 72f),
            text = listOf("Save"),
            role = "Button",
            actions = listOf("OnClick"),
        )

        val result = NodeSelector.select(
            nodes = listOf(button),
            tap = TapPoint(40f, 40f),
            options = SelectionOptions(maxCandidates = 0),
        )

        assertEquals("save-button", result.selectedNode?.uid)
        assertEquals("save-button", result.selection.selectedUid)
        assertEquals(SelectionKind.SEMANTICS_NODE, result.selection.kind)
        assertTrue(result.candidatesAtPoint.isEmpty())
    }

    @Test
    fun ignoresInvalidBoundsAndFallsBackWhenNoValidCandidateContainsTap() {
        val result = NodeSelector.select(
            nodes = listOf(
                node(
                    uid = "zero-size-button",
                    bounds = rect(30f, 30f, 30f, 30f),
                    text = listOf("Save"),
                    role = "Button",
                    actions = listOf("OnClick"),
                ),
            ),
            tap = TapPoint(30f, 30f),
        )

        assertNull(result.selectedNode)
        assertTrue(result.candidatesAtPoint.isEmpty())
        assertEquals(SelectionKind.TAP_POINT, result.selection.kind)
        assertEquals(SelectionConfidence.NONE, result.selection.confidence)
        assertEquals(SelectionSource.FALLBACK, result.selection.source)
    }

    @Test
    fun prefersEnabledCandidateOverDisabledClickableControl() {
        val disabledButton = node(
            uid = "disabled-pay-button",
            bounds = rect(20f, 20f, 180f, 76f),
            text = listOf("Pay now"),
            role = "Button",
            actions = listOf("OnClick"),
            enabled = false,
        )
        val enabledLabel = node(
            uid = "enabled-pay-label",
            bounds = rect(48f, 32f, 152f, 62f),
            text = listOf("Pay now"),
        )

        val result = NodeSelector.select(
            nodes = listOf(disabledButton, enabledLabel),
            tap = TapPoint(80f, 48f),
        )

        assertEquals("enabled-pay-label", result.selectedNode?.uid)
        assertTrue(result.candidatesAtPoint.first { it.node.uid == "disabled-pay-button" }.breakdown.getValue("disabledPenalty") < 0.0)
    }

    @Test
    fun createsHumanReadableScopeCandidateLabels() {
        val root = node(uid = "root", bounds = rect(0f, 0f, 500f, 800f))
        val row = node(
            uid = "settings-row",
            bounds = rect(16f, 40f, 480f, 104f),
            contentDescription = listOf("Notifications setting"),
            role = "Switch",
            testTag = "notificationsSwitch",
            actions = listOf("OnClick"),
        )
        val label = node(
            uid = "settings-label",
            bounds = rect(32f, 54f, 220f, 86f),
            text = listOf("Notifications"),
        )

        val result = NodeSelector.select(
            nodes = listOf(root, row, label),
            tap = TapPoint(60f, 68f),
            options = SelectionOptions(maxCandidates = 4),
        )

        val rowScope = result.scopeCandidates.firstOrNull { it.nodeUid == "settings-row" }
        assertNotNull(rowScope)
        assertTrue(rowScope!!.label.contains("Switch"))
        assertTrue(rowScope.label.contains("Notifications setting"))
        assertTrue(rowScope.label.contains("notificationsSwitch"))
        assertTrue(rowScope.label.contains("464x64"))
        assertFalse(rowScope.label.contains("FixThisNode("))
        assertEquals(result.scopeCandidates.sortedByDescending { it.score }, result.scopeCandidates)
    }

    @Test
    fun collectsNearbyMeaningfulNodesFromSameRootByDistanceAndSemanticIdentity() {
        val anchor = node(
            uid = "anchor",
            bounds = rect(40f, 40f, 140f, 90f),
            text = listOf("Selected"),
            role = "Button",
            actions = listOf("OnClick"),
        )
        val nearLabel = node(
            uid = "near-label",
            bounds = rect(150f, 40f, 260f, 90f),
            text = listOf("Nearby"),
        )
        val duplicateNearLabel = node(
            uid = "duplicate-near-label",
            bounds = rect(160f, 42f, 270f, 92f),
            text = listOf("Nearby"),
            editableText = "volatile draft",
        )
        val tagged = node(
            uid = "tagged",
            bounds = rect(280f, 40f, 360f, 90f),
            testTag = "secondaryAction",
        )
        val otherRoot = node(
            uid = "other-root-label",
            bounds = rect(145f, 40f, 255f, 90f),
            text = listOf("Other root"),
            rootIndex = 1,
        )
        val meaningless = node(
            uid = "meaningless",
            bounds = rect(150f, 100f, 260f, 160f),
        )

        val result = NodeSelector.select(
            nodes = listOf(anchor, nearLabel, duplicateNearLabel, tagged, otherRoot, meaningless),
            tap = TapPoint(70f, 60f),
            options = SelectionOptions(maxNearbyNodes = 4, nearbyRadiusPx = 400f),
        )

        assertEquals("anchor", result.selectedNode?.uid)
        assertEquals(listOf("near-label", "tagged"), result.nearbyNodes.map { it.uid })
    }

    private fun node(
        uid: String,
        bounds: FixThisRect,
        text: List<String> = emptyList(),
        contentDescription: List<String> = emptyList(),
        editableText: String? = null,
        role: String? = null,
        testTag: String? = null,
        actions: List<String> = emptyList(),
        rootIndex: Int = 0,
        treeKind: TreeKind = TreeKind.MERGED,
        enabled: Boolean = true,
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = rootIndex,
        treeKind = treeKind,
        boundsInWindow = bounds,
        text = text,
        editableText = editableText,
        contentDescription = contentDescription,
        role = role,
        testTag = testTag,
        actions = actions,
        enabled = enabled,
    )

    private fun rect(left: Float, top: Float, right: Float, bottom: Float): FixThisRect = FixThisRect(left = left, top = top, right = right, bottom = bottom)
}
