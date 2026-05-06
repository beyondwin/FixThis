package io.github.pointpatch.compose.sidekick.overlay

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PointPatchOverlayHostLayoutTest {
    @Test
    fun attachToAvoidsDuplicateWhenHostAlreadyExists() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val decorView = activity.window.decorView as ViewGroup
        val existingHost = FrameLayout(activity).apply {
            PointPatchOverlayHostLayout.markAsOverlayHost(this)
        }
        decorView.addView(existingHost)

        PointPatchOverlayHostLayout.attachTo(activity)

        assertEquals(1, decorView.findPointPatchOverlayHosts().size)
        assertSame(existingHost, decorView.findPointPatchOverlayHosts().single())
    }
}
