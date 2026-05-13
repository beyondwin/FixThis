@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package io.beyondwin.fixthis.compose.sidekick.bridge

import android.app.Activity
import android.app.Application
import io.beyondwin.fixthis.compose.sidekick.lifecycle.FixThisActivityLifecycleCallbacks
import java.lang.ref.WeakReference

internal object FixThisBridgeRuntime {
    private val lock = Any()
    private var server: BridgeServer? = null
    private var environment: AndroidBridgeEnvironment? = null
    internal val connectionState = BridgeConnectionState()

    fun start(
        application: Application,
        lifecycleCallbacks: FixThisActivityLifecycleCallbacks,
    ): Boolean = application.isDebuggable() && synchronized(lock) {
        if (server != null) {
            false
        } else {
            val store = SessionTokenStore(application)
            val session = store.create(application.packageName)
            val bridgeEnvironment = AndroidBridgeEnvironment(
                context = application,
                sidekickVersion = session.sidekickVersion,
                lifecycleCallbacks = lifecycleCallbacks,
            )
            val bridgeServer = BridgeServer(
                session = session,
                environment = bridgeEnvironment,
                connectionState = connectionState,
            )
            if (!bridgeServer.start()) {
                false
            } else {
                // Write the session AFTER bind succeeds so session.json reflects the
                // actual name BridgeServer is listening on (the bind retry may have
                // promoted us to a -1 / -2 suffix to dodge a stale prior binding).
                val resolved = bridgeServer.resolvedSocketName() ?: session.socketName
                val resolvedSession = if (resolved == session.socketName) {
                    session
                } else {
                    session.copy(socketName = resolved, socketAddress = "localabstract:$resolved")
                }
                store.write(resolvedSession)
                environment = bridgeEnvironment
                server = bridgeServer
                true
            }
        }
    }

    fun onActivityResumed(activity: Activity) {
        environment?.currentActivity = WeakReference(activity)
    }

    fun onActivityDestroyed(activity: Activity) {
        val current = environment?.currentActivity?.get()
        if (current === activity) {
            environment?.currentActivity = null
        }
    }

    fun stopForTest() {
        synchronized(lock) {
            server?.stop()
            server = null
            environment = null
        }
    }
}
