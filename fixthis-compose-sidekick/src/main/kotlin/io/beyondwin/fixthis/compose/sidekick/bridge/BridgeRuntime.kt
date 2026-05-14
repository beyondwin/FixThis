@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package io.beyondwin.fixthis.compose.sidekick.bridge

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.beyondwin.fixthis.compose.sidekick.lifecycle.FixThisActivityLifecycleCallbacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking // used only by stopForTest below
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference

internal object FixThisBridgeRuntime {
    private val mutex = Mutex()
    private var server: BridgeServer? = null
    private var environment: AndroidBridgeEnvironment? = null

    @Volatile
    private var startInFlight: Job? = null
    internal val connectionState = BridgeConnectionState()

    fun start(
        application: Application,
        lifecycleCallbacks: FixThisActivityLifecycleCallbacks,
    ): Boolean {
        if (!application.isDebuggable()) return false
        startInFlight = ProcessLifecycleOwner.get()
            .lifecycleScope.launch(Dispatchers.IO) {
                runCatching { startSuspending(application, lifecycleCallbacks) }
                    .onFailure { Log.w(BridgeRuntimeLogTag, "BridgeServer start failed", it) }
            }
        return true
    }

    private suspend fun startSuspending(
        application: Application,
        lifecycleCallbacks: FixThisActivityLifecycleCallbacks,
    ): Boolean = mutex.withLock {
        if (server != null) return@withLock false
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
        // stopForTest: runBlocking is permitted only here per spec §6.1.
        runBlocking(Dispatchers.IO) { // stopForTest
            mutex.withLock {
                server?.stop()
                server = null
                environment = null
                startInFlight?.cancelAndJoin()
                startInFlight = null
            }
        }
    }

    private const val BridgeRuntimeLogTag = "FixThisBridge"
}
