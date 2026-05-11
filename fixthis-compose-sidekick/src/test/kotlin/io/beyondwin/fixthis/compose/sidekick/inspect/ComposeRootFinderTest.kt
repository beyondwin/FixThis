@file:Suppress("DEPRECATION")

package io.beyondwin.fixthis.compose.sidekick.inspect

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.text.input.TextInputService
import androidx.compose.ui.unit.Density
import io.beyondwin.fixthis.compose.sidekick.overlay.FixThisConnectionStatusHostLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeRootFinderTest {
    @Test
    fun findRootsReturnsAttachedVisibleNonZeroRoot() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        val root = FakeRootForTestView(activity)
        activity.setContentView(content)

        content.addView(root, ViewGroup.LayoutParams(120, 80))
        content.measureAndLayout(width = 240, height = 160)
        root.layout(8, 12, 128, 92)

        val roots = ComposeRootFinder.findRoots(content)

        assertEquals(1, roots.size)
        assertSame(root, roots.single().view)
        assertEquals(0, roots.single().index)
    }

    @Test
    fun findRootsSkipsInvisibleDetachedAndZeroSizedRoots() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        val visibleRoot = FakeRootForTestView(activity)
        val invisibleRoot = FakeRootForTestView(activity).apply { visibility = View.INVISIBLE }
        val zeroWidthRoot = FakeRootForTestView(activity)
        val zeroHeightRoot = FakeRootForTestView(activity)
        val detachedRoot = FakeRootForTestView(activity)
        activity.setContentView(content)

        content.addView(visibleRoot, ViewGroup.LayoutParams(100, 100))
        content.addView(invisibleRoot, ViewGroup.LayoutParams(100, 100))
        content.addView(zeroWidthRoot, ViewGroup.LayoutParams(0, 100))
        content.addView(zeroHeightRoot, ViewGroup.LayoutParams(100, 0))
        content.measureAndLayout(width = 300, height = 300)
        visibleRoot.layout(0, 0, 100, 100)
        invisibleRoot.layout(0, 0, 100, 100)
        zeroWidthRoot.layout(0, 0, 0, 100)
        zeroHeightRoot.layout(0, 0, 100, 0)
        detachedRoot.layout(0, 0, 100, 100)

        val roots = ComposeRootFinder.findRoots(content)

        assertEquals(listOf(visibleRoot), roots.map { it.view })
    }

    @Test
    fun findRootsSkipsOverlayHostSubtree() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        val appRoot = FakeRootForTestView(activity)
        val overlayHost = FrameLayout(activity).apply {
            FixThisConnectionStatusHostLayout.markAsOverlayHost(this)
        }
        val overlayRoot = FakeRootForTestView(activity)
        activity.setContentView(content)

        content.addView(appRoot, ViewGroup.LayoutParams(100, 100))
        content.addView(overlayHost, ViewGroup.LayoutParams(100, 100))
        overlayHost.addView(overlayRoot, ViewGroup.LayoutParams(100, 100))
        content.measureAndLayout(width = 300, height = 300)
        appRoot.layout(0, 0, 100, 100)
        overlayHost.layout(0, 0, 100, 100)
        overlayRoot.layout(0, 0, 100, 100)

        val roots = ComposeRootFinder.findRoots(content)

        assertEquals(listOf(appRoot), roots.map { it.view })
    }
}

private fun View.measureAndLayout(width: Int, height: Int) {
    measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
    )
    layout(0, 0, width, height)
}

private class FakeRootForTestView(context: Context) :
    View(context),
    RootForTest {
    override val density: Density = Density(1f)
    override val semanticsOwner: SemanticsOwner
        get() = error("Semantics inspection is out of scope for these tests")

    @Deprecated("Use PlatformTextInputModifierNode instead.")
    override val textInputService: TextInputService
        get() = error("Text input is out of scope for these tests")

    override fun sendKeyEvent(keyEvent: KeyEvent): Boolean = false
}
