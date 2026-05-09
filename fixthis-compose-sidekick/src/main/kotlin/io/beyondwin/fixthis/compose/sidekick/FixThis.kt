package io.beyondwin.fixthis.compose.sidekick

import android.app.Application
import android.content.pm.ApplicationInfo
import io.beyondwin.fixthis.compose.sidekick.bridge.FixThisBridgeRuntime
import io.beyondwin.fixthis.compose.sidekick.lifecycle.FixThisActivityLifecycleCallbacks
import java.util.Collections
import java.util.WeakHashMap

object FixThis {
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
        FixThisBridgeRuntime.stopForTest()
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

        val callbacks = FixThisActivityLifecycleCallbacks()
        if (application.isDebuggable()) {
            FixThisBridgeRuntime.start(application, callbacks)
        }
        register(callbacks)
        return true
    }

    private fun Application.isDebuggable(): Boolean =
        runCatching {
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        }.getOrDefault(false)
}
