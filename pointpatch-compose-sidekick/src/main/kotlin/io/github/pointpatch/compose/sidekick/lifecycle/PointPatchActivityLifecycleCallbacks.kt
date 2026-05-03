package io.github.pointpatch.compose.sidekick.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.pointpatch.compose.sidekick.overlay.PointPatchOverlayHostLayout

class PointPatchActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        PointPatchOverlayHostLayout.attachTo(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
