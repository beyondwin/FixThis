package io.beyondwin.fixthis.compose.sidekick.lifecycle

import android.app.Activity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FixThisActivityLifecycleCallbacksTest {
    private val callbacks = FixThisActivityLifecycleCallbacks()

    private fun newActivity(): Activity = Robolectric.buildActivity(Activity::class.java).get()

    @Test
    fun `app is not foreground until activity resumed`() {
        assertFalse(callbacks.isAppForeground())
        assertNull(callbacks.lastResumedActivity())
    }

    @Test
    fun `resume increments counter and stores last activity`() {
        val activity = newActivity()
        callbacks.onActivityResumed(activity)
        assertTrue(callbacks.isAppForeground())
        assertSame(activity, callbacks.lastResumedActivity())
    }

    @Test
    fun `pause decrements counter`() {
        val activity = newActivity()
        callbacks.onActivityResumed(activity)
        callbacks.onActivityPaused(activity)
        assertFalse(callbacks.isAppForeground())
    }

    @Test
    fun `multiple activities track counter`() {
        val a1 = newActivity()
        val a2 = newActivity()
        callbacks.onActivityResumed(a1)
        callbacks.onActivityResumed(a2)
        assertTrue(callbacks.isAppForeground())
        assertSame(a2, callbacks.lastResumedActivity())
        callbacks.onActivityPaused(a2)
        assertTrue(callbacks.isAppForeground()) // a1 still resumed
        callbacks.onActivityPaused(a1)
        assertFalse(callbacks.isAppForeground())
    }
}
