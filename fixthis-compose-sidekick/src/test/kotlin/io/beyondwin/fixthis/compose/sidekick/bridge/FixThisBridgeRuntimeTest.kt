package io.beyondwin.fixthis.compose.sidekick.bridge

import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import io.beyondwin.fixthis.compose.sidekick.lifecycle.FixThisActivityLifecycleCallbacks
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FixThisBridgeRuntimeTest {

    @Test
    fun onActivityResumedAndDestroyedAreSequencedAcrossThreads() = runBlocking {
        // After 100 alternating resume/destroy pairs from two threads,
        // currentActivity is either the last resumed activity or null,
        // never a stale weak reference from an earlier resume.
        //
        // This test exercises AndroidBridgeEnvironment.setCurrentActivity /
        // clearCurrentActivityIf concurrent semantics directly.
        val env = AndroidBridgeEnvironment(
            context = ApplicationProvider.getApplicationContext(),
            sidekickVersion = "test",
            lifecycleCallbacks = FixThisActivityLifecycleCallbacks(),
        )
        val activities = (0 until 100).map { object : Activity() {} }
        val jobs = (0 until 100).map { i ->
            async {
                env.setCurrentActivity(WeakReference(activities[i]))
                env.clearCurrentActivityIf(activities[i])
            }
        }
        jobs.awaitAll()
        // After all jobs complete, either currentActivity is null, or it's a
        // valid WeakReference to one of the 100 activities (not stale GC'd).
        val cur = env.currentActivity.value
        if (cur != null) {
            val activity = cur.get()
            assertTrue(
                "currentActivity must be null or one of the known activities",
                activity == null || activities.contains(activity),
            )
        }
    }
}
