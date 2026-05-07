package io.beyondwin.fixthis.compose.sidekick.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.beyondwin.fixthis.compose.sidekick.bridge.FixThisBridgeRuntime
import io.beyondwin.fixthis.compose.sidekick.overlay.FixThisConnectionStatusHostLayout

class FixThisActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        FixThisConnectionStatusHostLayout.attachTo(activity)
        FixThisBridgeRuntime.onActivityResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        FixThisConnectionStatusHostLayout.detachFrom(activity)
    }

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        FixThisConnectionStatusHostLayout.detachFrom(activity)
        FixThisBridgeRuntime.onActivityDestroyed(activity)
    }
}
