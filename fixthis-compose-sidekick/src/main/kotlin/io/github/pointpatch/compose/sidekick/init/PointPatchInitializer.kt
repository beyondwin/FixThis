package io.github.pointpatch.compose.sidekick.init

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.startup.Initializer
import io.github.pointpatch.compose.sidekick.PointPatch

class PointPatchInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val applicationInfo = context.applicationInfo
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return
        }

        val application = context.applicationContext as? Application ?: return
        PointPatch.install(application)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
