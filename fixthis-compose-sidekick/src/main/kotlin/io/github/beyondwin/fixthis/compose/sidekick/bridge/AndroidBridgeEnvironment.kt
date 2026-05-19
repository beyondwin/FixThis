package io.github.beyondwin.fixthis.compose.sidekick.bridge

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.PowerManager
import android.util.DisplayMetrics
import io.github.beyondwin.fixthis.compose.core.model.FixThisError
import io.github.beyondwin.fixthis.compose.sidekick.inspect.SemanticsInspector
import io.github.beyondwin.fixthis.compose.sidekick.lifecycle.FixThisActivityLifecycleCallbacks
import io.github.beyondwin.fixthis.compose.sidekick.screenshot.ScreenshotCapturer
import io.github.beyondwin.fixthis.compose.sidekick.screenshot.ScreenshotStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID

internal class AndroidBridgeEnvironment(
    private val context: Context,
    private val sidekickVersion: String,
    private val lifecycleCallbacks: FixThisActivityLifecycleCallbacks,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val inspector: SemanticsInspector = SemanticsInspector(),
    private val screenshotCapturer: ScreenshotCapturer = ScreenshotCapturer(ScreenshotStore(context)),
) : BridgeEnvironment {
    private val _currentActivity = MutableStateFlow<WeakReference<Activity>?>(null)
    val currentActivity: StateFlow<WeakReference<Activity>?> = _currentActivity.asStateFlow()

    fun setCurrentActivity(ref: WeakReference<Activity>?) {
        _currentActivity.value = ref
    }

    fun clearCurrentActivityIf(activity: Activity) {
        _currentActivity.update { current ->
            if (current?.get() === activity) null else current
        }
    }

    private var lastScreenSnapshot: BridgeScreenSnapshot? = null
    private val buildInfoProvider: SidekickBuildInfoProvider = AndroidResourceSidekickBuildInfoProvider(context)

    @Volatile
    private var cachedSourceIndexResult: BridgeSourceIndexResult? = null
    private val navigationPerformer = AndroidNavigationPerformer(
        activityProvider = { _currentActivity.value?.get() },
        mainDispatcher = mainDispatcher,
    )

    override suspend fun status(): BridgeStatus {
        val inspection = inspectCurrentScreen()
        val sourceIndexResult = readSourceIndex()
        val buildInfo = buildInfoProvider.current()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val resumedActivity = lifecycleCallbacks.lastResumedActivity()
        return BridgeStatus(
            activity = inspection.activity,
            rootsCount = inspection.roots.size,
            sidekickVersion = sidekickVersion,
            bridgeProtocolVersion = BridgeProtocol.VERSION,
            sourceIndexAvailable = sourceIndexResult.sourceIndexAvailable,
            screenInteractive = powerManager?.isInteractive,
            keyguardLocked = keyguardManager?.isKeyguardLocked,
            appForeground = lifecycleCallbacks.isAppForeground(),
            pictureInPicture = resumedActivity?.let(::isActivityInPictureInPictureMode),
            installEpochMillis = readInstallEpochMillis(),
            sidekickBuildEpochMs = buildInfo.buildEpochMs,
        )
    }

    private fun readInstallEpochMillis(): Long? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrNull()

    override suspend fun inspectCurrentScreen(): BridgeScreenInspection = withContext(mainDispatcher) {
        val activity = _currentActivity.value?.get()
            ?: return@withContext BridgeScreenInspection(
                errors = listOf(FixThisError("NO_ACTIVITY", "No resumed Activity is available")),
            )
        val decorView = activity.window?.decorView
            ?: return@withContext BridgeScreenInspection(
                activity = activity::class.java.name,
                errors = listOf(FixThisError("NO_DECOR_VIEW", "Current Activity has no decorView")),
            )
        val result = inspector.inspect(decorView)
        BridgeScreenInspection(
            activity = activity::class.java.name,
            roots = result.roots.map { root ->
                BridgeInspectedRoot(
                    rootIndex = root.rootIndex,
                    boundsInWindow = root.boundsInWindow,
                    mergedNodes = root.mergedNodes,
                    unmergedNodes = root.unmergedNodes,
                )
            },
            errors = result.errors,
        )
    }

    override suspend fun captureScreenSnapshot(
        currentFocusOutput: String?,
    ): BridgeScreenSnapshot = readSourceIndex().let { sourceIndexResult ->
        withContext(mainDispatcher) {
            val sourceIndexAvailable = sourceIndexResult.sourceIndexAvailable
            val activity = _currentActivity.value?.get()
            if (activity == null) {
                val inspection = BridgeScreenInspection(
                    errors = listOf(FixThisError("NO_ACTIVITY", "No resumed Activity is available")),
                )
                val snapshot = BridgeScreenSnapshot(
                    inspection = inspection,
                    sourceIndexAvailable = sourceIndexAvailable,
                )
                lastScreenSnapshot = snapshot
                return@withContext snapshot
            }

            val inspection = inspectCurrentScreen()
            val screenshot = screenshotCapturer.capture(
                activity = activity,
                annotationId = "screen-${UUID.randomUUID()}",
                selectedBounds = null,
            )
            val displayMetrics: DisplayMetrics = activity.resources.displayMetrics
            val systemUiKind = SystemUiDetector.detect(activity, currentFocusOutput = currentFocusOutput)
            val snapshot = BridgeScreenSnapshot(
                activity = activity::class.java.name,
                inspection = inspection,
                screenshot = screenshot,
                sourceIndexAvailable = sourceIndexAvailable,
                orientation = mapOrientation(activity.resources.configuration.orientation),
                widthPx = displayMetrics.widthPixels,
                heightPx = displayMetrics.heightPixels,
                densityDpi = displayMetrics.densityDpi,
                windowMode = inferWindowMode(activity),
                systemUiVisible = systemUiKind != null,
                systemUiKind = systemUiKind,
            )
            lastScreenSnapshot = snapshot
            snapshot
        }
    }

    override suspend fun readSourceIndex(): BridgeSourceIndexResult {
        cachedSourceIndexResult?.let { return it }
        val result = withContext(Dispatchers.IO) {
            context.readSourceIndexResult("fixthis/fixthis-source-index.json")
        }
        cachedSourceIndexResult = result
        return result
    }

    override suspend fun getLastScreenSnapshot(): BridgeScreenSnapshot? = withContext(mainDispatcher) {
        lastScreenSnapshot
    }

    override suspend fun performNavigation(
        request: BridgeNavigationRequest,
    ): BridgeNavigationResult = navigationPerformer.perform(request)

    override fun screenshotCacheDirectory(): File = File(context.cacheDir, "fixthis")
}

internal fun Application.isDebuggable(): Boolean = runCatching {
    applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}.getOrDefault(false)
