package io.github.pointpatch.compose.sidekick.capture

import io.github.pointpatch.compose.core.model.ActivityInfo
import io.github.pointpatch.compose.core.model.AppInfo
import io.github.pointpatch.compose.core.model.PointPatchError
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.SelectionKind
import io.github.pointpatch.compose.core.model.SelectionSource
import io.github.pointpatch.compose.core.model.TapPoint
import io.github.pointpatch.compose.core.model.TreeKind
import io.github.pointpatch.compose.core.source.SourceIndex
import io.github.pointpatch.compose.core.source.SourceIndexEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationCaptureControllerTest {
    private val controller = AnnotationCaptureController(
        clock = { 1234L },
        idGenerator = { "annotation-1" }
    )

    @Test
    fun tapSelectBuildsAnnotationWithSelectionNearbyAndSourceCandidates() {
        val payButton = node(
            uid = "pay-button",
            bounds = rect(20f, 20f, 180f, 80f),
            text = listOf("Pay now"),
            role = "Button",
            actions = listOf("OnClick"),
            testTag = "payButton"
        )
        val totalLabel = node(
            uid = "total-label",
            bounds = rect(20f, 92f, 180f, 124f),
            text = listOf("Total")
        )

        val annotation = controller.capture(
            AnnotationCaptureInput(
                app = appInfo(),
                activity = activityInfo(),
                tap = TapPoint(42f, 40f),
                nodes = listOf(payButton, totalLabel),
                sourceIndex = SourceIndex(
                    entries = listOf(
                        SourceIndexEntry(
                            file = "CheckoutScreen.kt",
                            line = 42,
                            text = listOf("Pay now"),
                            testTags = listOf("payButton"),
                            roles = listOf("Button"),
                            activityNames = listOf("CheckoutActivity")
                        )
                    )
                ),
                userComment = "Wrong amount"
            )
        )

        assertEquals("annotation-1", annotation.id)
        assertEquals(1234L, annotation.createdAtEpochMillis)
        assertEquals("pay-button", annotation.selectedNode?.uid)
        assertEquals(SelectionKind.SEMANTICS_NODE, annotation.selection.kind)
        assertEquals(SelectionSource.TAP_SELECT, annotation.selection.source)
        assertEquals("pay-button", annotation.selection.selectedUid)
        assertEquals(listOf("total-label"), annotation.nearbyNodes.map { it.uid })
        assertEquals("CheckoutScreen.kt", annotation.sourceCandidates.single().file)
        assertEquals("Wrong amount", annotation.userComment)
        assertNull(annotation.screenshot)
    }

    @Test
    fun noNodeTapStillProducesAnnotationWithError() {
        val annotation = controller.capture(
            AnnotationCaptureInput(
                app = appInfo(),
                activity = activityInfo(),
                tap = TapPoint(400f, 400f),
                nodes = listOf(
                    node(
                        uid = "nearby-label",
                        bounds = rect(300f, 300f, 360f, 340f),
                        text = listOf("Nearby")
                    )
                ),
                userComment = "Missed target"
            )
        )

        assertNull(annotation.selectedNode)
        assertEquals(SelectionKind.TAP_POINT, annotation.selection.kind)
        assertEquals(SelectionSource.FALLBACK, annotation.selection.source)
        assertTrue(annotation.errors.any { it.code == "NO_NODE_AT_TAP" })
        assertEquals(listOf("nearby-label"), annotation.nearbyNodes.map { it.uid })
    }

    @Test
    fun scopeChipReselectionUpdatesSelectedNodeAndSelectionSource() {
        val parent = node(
            uid = "checkout-card",
            bounds = rect(10f, 10f, 220f, 160f),
            contentDescription = listOf("Checkout card")
        )
        val child = node(
            uid = "pay-button",
            bounds = rect(20f, 20f, 180f, 80f),
            text = listOf("Pay now"),
            role = "Button",
            actions = listOf("OnClick")
        )

        val annotation = controller.capture(
            AnnotationCaptureInput(
                app = appInfo(),
                activity = activityInfo(),
                tap = TapPoint(42f, 40f),
                nodes = listOf(parent, child),
                scopeNodeUid = "checkout-card",
                userComment = "Use wider scope"
            )
        )

        assertEquals("checkout-card", annotation.selectedNode?.uid)
        assertEquals(SelectionKind.SEMANTICS_NODE, annotation.selection.kind)
        assertEquals(SelectionSource.SCOPE_CHIP, annotation.selection.source)
        assertEquals("checkout-card", annotation.selection.selectedUid)
        assertEquals("Use wider scope", annotation.userComment)
    }

    @Test
    fun areaSelectFallbackCapturesVisualAreaWithoutSelectedNode() {
        val annotation = controller.capture(
            AnnotationCaptureInput(
                app = appInfo(),
                activity = activityInfo(),
                tap = TapPoint(30f, 30f),
                nodes = listOf(
                    node(
                        uid = "area-label",
                        bounds = rect(40f, 40f, 90f, 70f),
                        text = listOf("Area label")
                    )
                ),
                areaBoundsInWindow = rect(20f, 20f, 120f, 120f),
                userComment = "Visual issue"
            )
        )

        assertNull(annotation.selectedNode)
        assertEquals(SelectionKind.VISUAL_AREA, annotation.selection.kind)
        assertEquals(SelectionSource.AREA_SELECT, annotation.selection.source)
        assertEquals(rect(20f, 20f, 120f, 120f), annotation.selection.areaBoundsInWindow)
        assertEquals(listOf("area-label"), annotation.nearbyNodes.map { it.uid })
    }

    @Test
    fun areaSelectUsesStrongestContainedNodeForSourceCandidatesWithoutSelectingIt() {
        val centeredLabel = node(
            uid = "centered-label",
            bounds = rect(80f, 80f, 120f, 110f),
            text = listOf("Centered")
        )
        val payButton = node(
            uid = "pay-button",
            bounds = rect(150f, 150f, 190f, 190f),
            text = listOf("Pay now"),
            role = "Button",
            testTag = "payButton",
            actions = listOf("OnClick")
        )

        val annotation = controller.capture(
            AnnotationCaptureInput(
                app = appInfo(),
                activity = activityInfo(),
                tap = TapPoint(10f, 10f),
                nodes = listOf(centeredLabel, payButton),
                sourceIndex = SourceIndex(
                    entries = listOf(
                        SourceIndexEntry(
                            file = "CheckoutScreen.kt",
                            line = 42,
                            text = listOf("Pay now"),
                            testTags = listOf("payButton"),
                            roles = listOf("Button"),
                            activityNames = listOf("CheckoutActivity")
                        )
                    )
                ),
                areaBoundsInWindow = rect(0f, 0f, 200f, 200f),
                userComment = "Visual issue"
            )
        )

        assertNull(annotation.selectedNode)
        assertEquals(SelectionKind.VISUAL_AREA, annotation.selection.kind)
        assertEquals(SelectionSource.AREA_SELECT, annotation.selection.source)
        assertEquals(listOf("centered-label", "pay-button"), annotation.nearbyNodes.map { it.uid })
        assertEquals("CheckoutScreen.kt", annotation.sourceCandidates.single().file)
        assertTrue(annotation.sourceCandidates.single().matchReasons.contains("selected testTag"))
    }

    @Test
    fun captureCarriesInspectionErrorsForward() {
        val annotation = controller.capture(
            AnnotationCaptureInput(
                app = appInfo(),
                activity = activityInfo(),
                tap = TapPoint(1f, 1f),
                nodes = emptyList(),
                errors = listOf(PointPatchError("INSPECT_FAILED", "Root failed")),
                userComment = "No nodes"
            )
        )

        assertNotNull(annotation.errors.firstOrNull { it.code == "INSPECT_FAILED" })
        assertNotNull(annotation.errors.firstOrNull { it.code == "NO_NODE_AT_TAP" })
    }

    private fun node(
        uid: String,
        bounds: PointPatchRect,
        text: List<String> = emptyList(),
        contentDescription: List<String> = emptyList(),
        role: String? = null,
        testTag: String? = null,
        actions: List<String> = emptyList(),
        rootIndex: Int = 0,
        treeKind: TreeKind = TreeKind.MERGED,
        enabled: Boolean = true
    ): PointPatchNode = PointPatchNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = rootIndex,
        treeKind = treeKind,
        boundsInWindow = bounds,
        text = text,
        contentDescription = contentDescription,
        role = role,
        testTag = testTag,
        actions = actions,
        enabled = enabled
    )

    private fun appInfo(): AppInfo =
        AppInfo(packageName = "io.github.pointpatch.sample", versionName = "1.0", debuggable = true)

    private fun activityInfo(): ActivityInfo =
        ActivityInfo(className = "io.github.pointpatch.sample.CheckoutActivity")

    private fun rect(left: Float, top: Float, right: Float, bottom: Float): PointPatchRect =
        PointPatchRect(left = left, top = top, right = right, bottom = bottom)
}
