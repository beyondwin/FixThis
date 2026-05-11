package io.beyondwin.fixthis.compose.sidekick.init

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.startup.Initializer
import io.beyondwin.fixthis.compose.sidekick.FixThis
import java.util.concurrent.atomic.AtomicBoolean

class FixThisInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val applicationInfo = context.applicationInfo
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            // Defence-in-depth: the manifest split should prevent this Initializer
            // from being registered in non-debuggable builds. If it nevertheless
            // runs (e.g. an app promoted the sidekick to a release `implementation`
            // and otherwise defeated the manifest split), log once and bail.
            if (releaseWarningLogged.compareAndSet(false, true)) {
                Log.w(TAG, "FixThis sidekick is not attaching: release build detected.")
            }
            return
        }

        val application = context.applicationContext as? Application ?: return
        FixThis.install(application)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private companion object {
        private const val TAG = "FixThisInitializer"
        private val releaseWarningLogged = AtomicBoolean(false)
    }
}
