package io.beyondwin.fixthis.compose.sidekick.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.beyondwin.fixthis.compose.sidekick.bridge.FixThisBridgeRuntime
import io.beyondwin.fixthis.compose.sidekick.overlay.FixThisOverlayHostLayout

class FixThisActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        FixThisOverlayHostLayout.attachTo(activity)
        FixThisBridgeRuntime.onActivityResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        FixThisBridgeRuntime.onActivityDestroyed(activity)
    }
}
