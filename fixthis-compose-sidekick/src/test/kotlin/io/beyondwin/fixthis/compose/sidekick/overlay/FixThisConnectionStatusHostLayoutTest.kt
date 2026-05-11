package io.beyondwin.fixthis.compose.sidekick.overlay

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.beyondwin.fixthis.compose.sidekick.bridge.BridgeConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FixThisConnectionStatusHostLayoutTest {
    @Test
    fun attachToKeepsStatusHostOutsideActivityDecorWindow() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val decorView = activity.window.decorView as ViewGroup

        FixThisConnectionStatusHostLayout.attachTo(activity)

        assertEquals(emptyList<View>(), decorView.findFixThisOverlayHosts())
    }

    @Test
    fun attachToUsesPermissionlessApplicationPanelWindow() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        FixThisConnectionStatusHostLayout.attachTo(activity)

        val host = activity.statusWindowHosts().single()
        val params = host.layoutParams as WindowManager.LayoutParams
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, params.type)
        assertEquals(activity.window.decorView.windowToken, params.token)
    }

    @Test
    fun attachToAvoidsDuplicateWhenWindowAlreadyExists() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        FixThisConnectionStatusHostLayout.attachTo(activity)
        FixThisConnectionStatusHostLayout.attachTo(activity)

        assertEquals(1, activity.statusWindowHosts().size)
    }

    @Test
    fun detachFromRemovesStatusWindow() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        FixThisConnectionStatusHostLayout.attachTo(activity)
        FixThisConnectionStatusHostLayout.detachFrom(activity)

        assertEquals(emptyList<View>(), activity.statusWindowHosts())
    }

    @Test
    fun attachToAvoidsDuplicateWhenHostAlreadyExistsInDecor() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val decorView = activity.window.decorView as ViewGroup
        val existingHost = FrameLayout(activity).apply {
            FixThisConnectionStatusHostLayout.markAsOverlayHost(this)
        }
        decorView.addView(existingHost)

        FixThisConnectionStatusHostLayout.attachTo(activity)

        assertEquals(1, decorView.findFixThisOverlayHosts().size)
        assertSame(existingHost, decorView.findFixThisOverlayHosts().single())
    }

    @Test
    fun rendersWaitingOrConnectedStatusOnly() {
        var now = 1_000L
        val state = BridgeConnectionState(clock = { now }, connectedWindowMillis = 500L)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        val host = FixThisConnectionStatusHostLayout(activity, state)

        assertEquals("MCP waiting", host.statusText())

        state.markAuthorizedRequest()
        host.forceStatusRefreshForTest()

        assertEquals("MCP connected", host.statusText())

        now += 501L
        host.forceStatusRefreshForTest()

        assertEquals("MCP waiting", host.statusText())
    }

    @Test
    fun statusPillBackgroundIsTranslucent() {
        var now = 1_000L
        val state = BridgeConnectionState(clock = { now }, connectedWindowMillis = 500L)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FixThisConnectionStatusHostLayout(activity, state)

        assertEquals(TranslucentPillAlpha, host.statusPillBackgroundAlpha())

        state.markAuthorizedRequest()
        host.forceStatusRefreshForTest()

        assertEquals(TranslucentPillAlpha, host.statusPillBackgroundAlpha())
    }

    @Test
    fun statusPillUsesCompactSpacing() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FixThisConnectionStatusHostLayout(activity)
        val pill = host.statusPill()
        val iconParams = host.statusIcon().layoutParams as LinearLayout.LayoutParams

        assertEquals(activity.dp(CompactHorizontalPaddingDp), pill.paddingLeft)
        assertEquals(activity.dp(CompactHorizontalPaddingDp), pill.paddingRight)
        assertEquals(activity.dp(CompactVerticalPaddingDp), pill.paddingTop)
        assertEquals(activity.dp(CompactVerticalPaddingDp), pill.paddingBottom)
        assertEquals(activity.dp(CompactIconTextGapDp), iconParams.marginEnd)
    }

    @Test
    fun connectedStateDoesNotRecreateTextViewOrAnimateWholeViews() {
        var now = 1_000L
        val state = BridgeConnectionState(clock = { now }, connectedWindowMillis = 500L)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FixThisConnectionStatusHostLayout(activity, state)
        val textView = host.statusTextView()

        assertEquals("MCP waiting", textView.text.toString())
        assertEquals(false, host.statusIcon().hasTransientState())

        state.markAuthorizedRequest()
        host.forceStatusRefreshForTest()

        assertEquals("MCP connected", textView.text.toString())
        assertEquals(false, host.statusIcon().hasTransientState())
        assertSame(textView, host.statusTextView())

        host.forceStatusRefreshForTest()

        assertEquals("MCP connected", textView.text.toString())
        assertSame(textView, host.statusTextView())

        now += 501L
        host.forceStatusRefreshForTest()

        assertEquals("MCP waiting", textView.text.toString())
        assertEquals(false, host.statusIcon().hasTransientState())
        assertSame(textView, host.statusTextView())
    }

    @Test
    fun connectedIndicatorRunsIconOnlyPulseAnimation() {
        var now = 1_000L
        val state = BridgeConnectionState(clock = { now }, connectedWindowMillis = 500L)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FixThisConnectionStatusHostLayout(activity, state)
        (activity.window.decorView as ViewGroup).addView(host)
        val icon = host.statusIcon()
        val textView = host.statusTextView()

        assertEquals(false, icon.isStatusIconPulseRunningForTest())

        state.markAuthorizedRequest()
        host.forceStatusRefreshForTest()

        assertEquals(true, icon.isStatusIconPulseRunningForTest())
        assertEquals(1f, icon.alpha)
        assertEquals(1f, icon.scaleX)
        assertEquals(1f, icon.scaleY)
        assertEquals(1f, textView.alpha)
        assertEquals(1f, textView.scaleX)
        assertEquals(1f, textView.scaleY)

        now += 501L
        host.forceStatusRefreshForTest()

        assertEquals(false, icon.isStatusIconPulseRunningForTest())
    }

    @Test
    fun connectedIndicatorDoesNotAnimateAlphaOrScale() {
        var now = 1_000L
        val state = BridgeConnectionState(clock = { now }, connectedWindowMillis = 500L)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FixThisConnectionStatusHostLayout(activity, state)

        state.markAuthorizedRequest()
        host.forceStatusRefreshForTest()

        assertEquals(1f, host.statusIcon().alpha)
        assertEquals(1f, host.statusIcon().scaleX)
        assertEquals(1f, host.statusIcon().scaleY)
    }

    @Test
    fun statusTopMarginKeepsPillBelowStatusBar() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val statusBarInsetPx = 48
        val expectedGapPx = activity.resources.displayMetrics.density.times(8).toInt()

        assertEquals(
            statusBarInsetPx + expectedGapPx,
            statusSafeTopMarginPx(activity, statusBarInsetPx),
        )
    }
}

private fun FixThisConnectionStatusHostLayout.statusText(): String = statusTextView().text.toString()

private fun FixThisConnectionStatusHostLayout.statusPill(): LinearLayout = getChildAt(0) as LinearLayout

private fun FixThisConnectionStatusHostLayout.statusPillBackgroundAlpha(): Int {
    val background = statusPill().background as GradientDrawable
    return Color.alpha(requireNotNull(background.color).defaultColor)
}

private fun FixThisConnectionStatusHostLayout.statusIcon(): View = (getChildAt(0) as LinearLayout).getChildAt(0)

private fun FixThisConnectionStatusHostLayout.statusTextView(): TextView = (getChildAt(0) as LinearLayout).getChildAt(1) as TextView

private fun FixThisConnectionStatusHostLayout.forceStatusRefreshForTest() {
    val method = javaClass.getDeclaredMethod("updateStatus")
    method.isAccessible = true
    method.invoke(this)
}

private fun View.isStatusIconPulseRunningForTest(): Boolean {
    val method = javaClass.getDeclaredMethod("isPulseRunningForTest")
    method.isAccessible = true
    return method.invoke(this) as Boolean
}

private fun Activity.statusWindowHosts(): List<View> {
    val windowManager = getSystemService(WindowManager::class.java)
    return (Shadows.shadowOf(windowManager) as ShadowWindowManagerImpl)
        .views
        .filter { it.isFixThisOverlayHost() }
}

private fun Activity.dp(value: Int): Int = resources.displayMetrics.density.times(value).toInt()

private const val TranslucentPillAlpha = 170
private const val CompactHorizontalPaddingDp = 10
private const val CompactVerticalPaddingDp = 6
private const val CompactIconTextGapDp = 8
