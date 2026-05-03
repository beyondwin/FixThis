package io.github.pointpatch.compose.sidekick

import android.app.Application
import io.github.pointpatch.compose.sidekick.lifecycle.PointPatchActivityLifecycleCallbacks
import java.util.Collections
import java.util.WeakHashMap

object PointPatch {
    private val lock = Any()
    private val registeredApplications = Collections.newSetFromMap(WeakHashMap<Application, Boolean>())

    fun install(application: Application) {
        install(application) { callbacks ->
            application.registerActivityLifecycleCallbacks(callbacks)
        }
    }

    internal fun installForTest(
        application: Application,
        register: (Application.ActivityLifecycleCallbacks) -> Unit,
    ): Boolean = install(application, register)

    internal fun resetForTest() {
        synchronized(lock) {
            registeredApplications.clear()
        }
    }

    private fun install(
        application: Application,
        register: (Application.ActivityLifecycleCallbacks) -> Unit,
    ): Boolean {
        synchronized(lock) {
            if (!registeredApplications.add(application)) {
                return false
            }
        }

        register(PointPatchActivityLifecycleCallbacks())
        return true
    }
}
