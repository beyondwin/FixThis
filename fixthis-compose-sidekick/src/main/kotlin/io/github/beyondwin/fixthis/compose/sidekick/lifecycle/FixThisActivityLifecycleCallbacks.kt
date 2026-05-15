package io.github.beyondwin.fixthis.compose.sidekick.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.beyondwin.fixthis.compose.sidekick.bridge.FixThisBridgeRuntime
import io.github.beyondwin.fixthis.compose.sidekick.overlay.FixThisConnectionStatusHostLayout
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

class FixThisActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    private val resumedCounter = AtomicInteger(0)

    @Volatile private var lastResumed: WeakReference<Activity>? = null

    fun isAppForeground(): Boolean = resumedCounter.get() > 0

    fun lastResumedActivity(): Activity? = lastResumed?.get()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        resumedCounter.incrementAndGet()
        lastResumed = WeakReference(activity)
        FixThisConnectionStatusHostLayout.attachTo(activity)
        FixThisBridgeRuntime.onActivityResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        resumedCounter.updateAndGet { current -> if (current > 0) current - 1 else 0 }
        FixThisConnectionStatusHostLayout.detachFrom(activity)
    }

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        FixThisConnectionStatusHostLayout.detachFrom(activity)
        FixThisBridgeRuntime.onActivityDestroyed(activity)
    }
}
