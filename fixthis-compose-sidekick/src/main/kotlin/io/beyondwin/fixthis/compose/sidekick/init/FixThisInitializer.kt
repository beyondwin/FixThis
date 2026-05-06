package io.beyondwin.fixthis.compose.sidekick.init

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.startup.Initializer
import io.beyondwin.fixthis.compose.sidekick.FixThis

class FixThisInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val applicationInfo = context.applicationInfo
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return
        }

        val application = context.applicationContext as? Application ?: return
        FixThis.install(application)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
