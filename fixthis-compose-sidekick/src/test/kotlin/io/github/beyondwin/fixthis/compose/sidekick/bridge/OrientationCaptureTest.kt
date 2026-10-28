package io.github.beyondwin.fixthis.compose.sidekick.bridge

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beyondwin.fixthis.compose.sidekick.lifecycle.FixThisActivityLifecycleCallbacks
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.lang.ref.WeakReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OrientationCaptureTest {
    @Test
    fun `mapOrientation returns PORTRAIT for portrait configuration`() {
        assertEquals("PORTRAIT", mapOrientation(Configuration.ORIENTATION_PORTRAIT))
    }

    @Test
    fun `mapOrientation returns LANDSCAPE for landscape configuration`() {
        assertEquals("LANDSCAPE", mapOrientation(Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun `mapOrientation returns null for undefined configuration`() {
        assertNull(mapOrientation(Configuration.ORIENTATION_UNDEFINED))
    }

    @Test
    fun `mapWindowMode returns PIP when picture-in-picture takes precedence over multi-window`() {
        assertEquals("PIP", mapWindowMode(isPip = true, isMultiWindow = true))
        assertEquals("PIP", mapWindowMode(isPip = true, isMultiWindow = false))
    }

    @Test
    fun `mapWindowMode returns SPLIT_SCREEN when only multi-window is active`() {
        assertEquals("SPLIT_SCREEN", mapWindowMode(isPip = false, isMultiWindow = true))
    }

    @Test
    fun `mapWindowMode returns FULLSCREEN by default`() {
        assertEquals("FULLSCREEN", mapWindowMode(isPip = false, isMultiWindow = false))
    }

    @Test
    @Config(sdk = [23])
    fun `inferWindowMode returns FULLSCREEN below picture-in-picture API`() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        assertEquals("FULLSCREEN", inferWindowMode(activity))
    }

    @Test(timeout = 10_000)
    @LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
    @Config(qualifiers = "w360dp-h640dp-port-mdpi")
    fun `captureScreenSnapshot populates orientation and display metrics from activity`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val lifecycle = FixThisActivityLifecycleCallbacks()
        var controller: ActivityController<Activity>? = null
        val environment = AndroidBridgeEnvironment(
            context = app,
            sidekickVersion = "test",
            lifecycleCallbacks = lifecycle,
        )
        try {
            instrumentation.runOnMainSync {
                controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
                val activity = checkNotNull(controller).get()
                lifecycle.onActivityResumed(activity)
                environment.setCurrentActivity(WeakReference(activity))
            }

            // PixelCopy completes through a main-looper callback. Keep the real
            // dispatcher/capturer and wait on the separate instrumentation test thread.
            val snapshot = withTimeout(5_000) { environment.captureScreenSnapshot() }

            assertEquals("PORTRAIT", snapshot.orientation)
            assertEquals(360, snapshot.widthPx)
            assertEquals(640, snapshot.heightPx)
            assertEquals(160, snapshot.densityDpi)
            assertEquals("FULLSCREEN", snapshot.windowMode)
            assertEquals(false, snapshot.systemUiVisible)
            assertNull(snapshot.systemUiKind)
            assertNotNull(snapshot.screenshot)
            assertNull(snapshot.screenshot?.captureFailedReason)
            assertTrue(
                "capture must write screenshot evidence",
                File(checkNotNull(snapshot.screenshot?.fullPath)).length() > 0,
            )
            assertEquals(snapshot, withTimeout(5_000) { environment.getLastScreenSnapshot() })
        } finally {
            instrumentation.runOnMainSync {
                controller?.let {
                    environment.clearCurrentActivityIf(it.get())
                    lifecycle.onActivityPaused(it.get())
                    it.pause().stop().destroy()
                }
            }
        }
    }
}
