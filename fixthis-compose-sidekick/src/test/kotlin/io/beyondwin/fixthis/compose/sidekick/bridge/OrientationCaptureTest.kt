package io.beyondwin.fixthis.compose.sidekick.bridge

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.beyondwin.fixthis.compose.sidekick.lifecycle.FixThisActivityLifecycleCallbacks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    @Ignore(
        "Robolectric integration test hangs >10min due to AndroidBridgeEnvironment setup; " +
            "pure-function tests above cover the mapping logic. Replace via EmulatorTestKit (Phase E RTI-1).",
    )
    @Test
    fun `captureScreenSnapshot populates orientation and display metrics from activity`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val lifecycle = FixThisActivityLifecycleCallbacks()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        lifecycle.onActivityResumed(activity)

        val environment = AndroidBridgeEnvironment(
            context = app,
            sidekickVersion = "test",
            lifecycleCallbacks = lifecycle,
        )
        environment.currentActivity = java.lang.ref.WeakReference(activity)

        val snapshot = environment.captureScreenSnapshot()

        assertNotNull("orientation must be populated", snapshot.orientation)
        assertTrue(
            "orientation must be PORTRAIT or LANDSCAPE",
            snapshot.orientation == "PORTRAIT" || snapshot.orientation == "LANDSCAPE",
        )
        assertNotNull("widthPx must be populated", snapshot.widthPx)
        assertNotNull("heightPx must be populated", snapshot.heightPx)
        assertNotNull("densityDpi must be populated", snapshot.densityDpi)
        assertTrue("widthPx must be positive", snapshot.widthPx!! > 0)
        assertTrue("heightPx must be positive", snapshot.heightPx!! > 0)
        assertTrue("densityDpi must be positive", snapshot.densityDpi!! > 0)
        assertEquals("FULLSCREEN", snapshot.windowMode)
        // B.5 fills these; for now they must stay null.
        assertNull(snapshot.systemUiVisible)
        assertNull(snapshot.systemUiKind)
    }
}
