package io.beyondwin.fixthis.compose.sidekick.overlay

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import io.beyondwin.fixthis.compose.sidekick.bridge.BridgeConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FixThisConnectionStatusHostLayoutTest {
    @Test
    fun attachToAvoidsDuplicateWhenHostAlreadyExists() {
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
}

private fun FixThisConnectionStatusHostLayout.statusText(): String =
    ((getChildAt(0) as android.widget.TextView).text).toString()

private fun FixThisConnectionStatusHostLayout.forceStatusRefreshForTest() {
    val method = javaClass.getDeclaredMethod("updateStatus")
    method.isAccessible = true
    method.invoke(this)
}
