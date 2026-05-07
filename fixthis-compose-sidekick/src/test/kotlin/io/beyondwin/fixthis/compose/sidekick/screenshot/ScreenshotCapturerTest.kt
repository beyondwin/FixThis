package io.beyondwin.fixthis.compose.sidekick.screenshot

import android.app.Activity
import android.graphics.Bitmap
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.beyondwin.fixthis.compose.sidekick.overlay.FixThisConnectionStatusHostLayout
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenshotCapturerTest {
    @Test
    fun captureDoesNotHideConnectionStatusHostBeforePixelCopy() = runBlocking {
        val activity = measuredActivity()
        val decorView = activity.window.decorView as ViewGroup
        val host = FrameLayout(activity).apply {
            FixThisConnectionStatusHostLayout.markAsOverlayHost(this)
            visibility = View.VISIBLE
        }
        decorView.addView(host, ViewGroup.LayoutParams(100, 100))
        var pixelCopyRequestCount = 0
        var hostVisibilityAtPixelCopy = View.GONE
        val capturer = ScreenshotCapturer(
            store = ScreenshotStore(activity),
            mainDispatcher = Dispatchers.Unconfined,
            pixelCopyRequester = PixelCopyRequester { _, _, onFinished, _ ->
                pixelCopyRequestCount += 1
                hostVisibilityAtPixelCopy = host.visibility
                onFinished(PixelCopy.SUCCESS)
            },
        )

        val capture = async(start = CoroutineStart.UNDISPATCHED) {
            capturer.capture(activity = activity, annotationId = "annotation-1")
        }

        val info = capture.await()

        assertNotNull(info.fullPath)
        assertEquals(1, pixelCopyRequestCount)
        assertEquals(View.VISIBLE, hostVisibilityAtPixelCopy)
        assertEquals(View.VISIBLE, host.visibility)
    }

    @Test
    fun timeoutDoesNotRecyclePixelCopyBitmapThatCanStillReceiveCallback() = runBlocking {
        val activity = measuredActivity()
        var requestedBitmap: Bitmap? = null
        val capturer = ScreenshotCapturer(
            store = ScreenshotStore(activity),
            pixelCopyTimeoutMillis = 1L,
            mainDispatcher = Dispatchers.Default,
            pixelCopyRequester = PixelCopyRequester { _, bitmap, _, _ ->
                requestedBitmap = bitmap
            },
        )

        val info = capturer.capture(activity = activity, annotationId = "annotation-1")

        assertNotNull(info.fullPath)
        assertFalse(requireNotNull(requestedBitmap).isRecycled)
    }

    @Test
    @Config(sdk = [25])
    fun captureKeepsConnectionStatusHostVisibleOnCanvasFallback() = runBlocking {
        val activity = measuredActivity()
        val decorView = activity.window.decorView as ViewGroup
        val host = FrameLayout(activity).apply {
            FixThisConnectionStatusHostLayout.markAsOverlayHost(this)
            visibility = View.VISIBLE
        }
        decorView.addView(host, ViewGroup.LayoutParams(100, 100))
        val capturer = ScreenshotCapturer(store = ScreenshotStore(activity))

        capturer.capture(activity = activity, annotationId = "annotation-1")

        assertEquals(View.VISIBLE, host.visibility)
    }

    private fun measuredActivity(): Activity {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        activity.setContentView(content)
        content.addView(FrameLayout(activity), ViewGroup.LayoutParams(100, 100))
        val decorView = activity.window.decorView
        decorView.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        decorView.layout(0, 0, 200, 200)
        return activity
    }

}
