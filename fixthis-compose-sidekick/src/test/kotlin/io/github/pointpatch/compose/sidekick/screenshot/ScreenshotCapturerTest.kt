package io.github.pointpatch.compose.sidekick.screenshot

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.pointpatch.compose.sidekick.overlay.PointPatchOverlayHostLayout
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenshotCapturerTest {
    @Test
    fun captureWaitsForDrawCompletionAfterPreDrawBeforePixelCopyAndRestoresOverlayHost() = runBlocking {
        val activity = measuredActivity()
        val decorView = activity.window.decorView as ViewGroup
        val host = FrameLayout(activity).apply {
            PointPatchOverlayHostLayout.markAsOverlayHost(this)
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

        assertEquals(0, pixelCopyRequestCount)
        assertEquals(View.INVISIBLE, host.visibility)

        decorView.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(0, pixelCopyRequestCount)

        decorView.viewTreeObserver.dispatchOnDraw()
        assertEquals(0, pixelCopyRequestCount)

        shadowOf(Looper.getMainLooper()).idle()
        val info = capture.await()

        assertNotNull(info.fullPath)
        assertEquals(1, pixelCopyRequestCount)
        assertEquals(View.INVISIBLE, hostVisibilityAtPixelCopy)
        assertEquals(View.VISIBLE, host.visibility)
    }

    @Test
    fun cancellationAfterHidingOverlayHostRestoresVisibility() = runBlocking {
        val activity = measuredActivity()
        val decorView = activity.window.decorView as ViewGroup
        val host = CancellingOnHideFrameLayout(activity).apply {
            PointPatchOverlayHostLayout.markAsOverlayHost(this)
            visibility = View.VISIBLE
        }
        decorView.addView(host, ViewGroup.LayoutParams(100, 100))
        val capturer = ScreenshotCapturer(
            store = ScreenshotStore(activity),
            pixelCopyRequester = PixelCopyRequester { _, _, onFinished, _ ->
                onFinished(PixelCopy.SUCCESS)
            },
        )

        val capture = launch(start = CoroutineStart.LAZY) {
            capturer.capture(activity = activity, annotationId = "annotation-1")
        }
        host.onHide = { capture.cancel() }
        capture.start()
        capture.join()

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
    fun captureHidesOverlayHostsAndRestoresVisibility() = runBlocking {
        val activity = measuredActivity()
        val decorView = activity.window.decorView as ViewGroup
        val host = FrameLayout(activity).apply {
            PointPatchOverlayHostLayout.markAsOverlayHost(this)
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

    private class CancellingOnHideFrameLayout(context: Context) : FrameLayout(context) {
        var onHide: () -> Unit = {}

        override fun setVisibility(visibility: Int) {
            super.setVisibility(visibility)
            if (visibility == View.INVISIBLE) {
                onHide()
            }
        }
    }
}
