package io.beyondwin.fixthis.compose.console.studio.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import io.beyondwin.fixthis.compose.console.studio.model.RectPercent
import io.beyondwin.fixthis.compose.console.studio.model.StudioTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasHelpersTest {
    @Test
    fun previewGestureEnabledOnlyInAnnotateModeWithAvailableAnnotation() {
        assertTrue(isPreviewGestureEnabled(StudioTool.ANNOTATE, CanvasInteractionMode.WidgetSnapAndRegion))
        assertFalse(isPreviewGestureEnabled(StudioTool.SELECT, CanvasInteractionMode.WidgetSnapAndRegion))
        assertFalse(isPreviewGestureEnabled(StudioTool.ANNOTATE, CanvasInteractionMode.AnnotationUnavailable))
    }

    @Test
    fun offsetToPercentClampsCoordinatesToPreviewBounds() {
        assertPercent(
            expected = Offset(25f, 50f),
            actual = offsetToPercent(Offset(50f, 200f), IntSize(width = 200, height = 400)),
        )
        assertPercent(
            expected = Offset(0f, 100f),
            actual = offsetToPercent(Offset(-20f, 500f), IntSize(width = 200, height = 400)),
        )
    }

    @Test
    fun offsetToPercentReturnsZeroWhenPreviewHasNoSize() {
        assertPercent(
            expected = Offset.Zero,
            actual = offsetToPercent(Offset(40f, 80f), IntSize(width = 0, height = 400)),
        )
        assertPercent(
            expected = Offset.Zero,
            actual = offsetToPercent(Offset(40f, 80f), IntSize(width = 200, height = 0)),
        )
    }

    @Test
    fun rectPercentToPixelBoundsClampsRectInsidePreview() {
        val bounds = rectPercentToPixelBounds(
            rect = RectPercent(x = -10f, y = 25f, w = 120f, h = 80f),
            previewSize = IntSize(width = 300, height = 600),
        )

        assertRect(
            expected = Rect(left = 0f, top = 150f, right = 300f, bottom = 600f),
            actual = bounds,
        )
    }

    @Test
    fun rectToPercentBoundsConvertsAndClampsWidgetBounds() {
        val percent = rectToPercentBounds(
            rectPx = Rect(left = -20f, top = 120f, right = 220f, bottom = 360f),
            previewSize = IntSize(width = 200, height = 600),
        )

        assertRectPercent(
            expected = RectPercent(x = 0f, y = 20f, w = 100f, h = 40f),
            actual = percent,
        )
    }

    @Test
    fun widgetRegistryHitTestReturnsSmallestContainingEntry() {
        val registry = WidgetRegistry()
        registry.register("screen", Rect(left = 0f, top = 0f, right = 300f, bottom = 600f))
        registry.register("balance-card", Rect(left = 24f, top = 120f, right = 276f, bottom = 260f))
        registry.register("balance-title", Rect(left = 44f, top = 140f, right = 180f, bottom = 172f))

        assertEquals("balance-title", registry.hitTest(Offset(50f, 150f))?.tag)
        assertEquals("balance-card", registry.hitTest(Offset(260f, 250f))?.tag)
    }

    @Test
    fun widgetRegistryHitTestReturnsNullOutsideEntries() {
        val registry = WidgetRegistry()
        registry.register("greeting", Rect(left = 20f, top = 80f, right = 180f, bottom = 112f))

        assertNull(registry.hitTest(Offset(10f, 10f)))
    }
}

private fun assertPercent(expected: Offset, actual: Offset) {
    assertEquals(expected.x, actual.x, 0.0001f)
    assertEquals(expected.y, actual.y, 0.0001f)
}

private fun assertRect(expected: Rect, actual: Rect) {
    assertEquals(expected.left, actual.left, 0.0001f)
    assertEquals(expected.top, actual.top, 0.0001f)
    assertEquals(expected.right, actual.right, 0.0001f)
    assertEquals(expected.bottom, actual.bottom, 0.0001f)
}

private fun assertRectPercent(expected: RectPercent, actual: RectPercent?) {
    requireNotNull(actual)
    assertEquals(expected.x, actual.x, 0.0001f)
    assertEquals(expected.y, actual.y, 0.0001f)
    assertEquals(expected.w, actual.w, 0.0001f)
    assertEquals(expected.h, actual.h, 0.0001f)
}
