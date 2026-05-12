package io.beyondwin.fixthis.compose.sidekick.bridge

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.PowerManager
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import io.beyondwin.fixthis.compose.core.model.FixThisError
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.ScreenshotInfo
import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.sidekick.BuildInfo
import io.beyondwin.fixthis.compose.sidekick.inspect.SemanticsInspector
import io.beyondwin.fixthis.compose.sidekick.lifecycle.FixThisActivityLifecycleCallbacks
import io.beyondwin.fixthis.compose.sidekick.screenshot.ScreenshotCapturer
import io.beyondwin.fixthis.compose.sidekick.screenshot.ScreenshotStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.UUID

class BridgeServer(
    private val session: SidekickSession,
    private val environment: BridgeEnvironment,
    private val connectionState: BridgeConnectionState = BridgeConnectionState(),
    private val socketFactory: (String) -> LocalServerSocket = { socketName -> LocalServerSocket(socketName) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var serverSocket: LocalServerSocket? = null

    @Volatile
    private var resolvedName: String? = null

    /**
     * The actual socket name [start] bound to (may differ from the session's
     * configured name if a stale prior binding forced a suffix fallback;
     * see [BridgeSocketNameNegotiator]). `null` before [start] succeeds or
     * after [stop].
     */
    fun resolvedSocketName(): String? = resolvedName

    fun start(): Boolean {
        if (serverSocket != null) return false
        val attempted = mutableListOf<String>()
        var lastError: Throwable? = null
        for (attempt in 0 until BridgeSocketNameNegotiator.MaxAttempts) {
            val candidate = BridgeSocketNameNegotiator.nextCandidate(session.socketName, attempt)
            attempted += candidate
            val socket = try {
                socketFactory(candidate)
            } catch (error: IOException) {
                lastError = error
                continue
            }
            serverSocket = socket
            resolvedName = candidate
            scope.launch {
                acceptLoop(socket)
            }
            return true
        }
        Log.w(
            BridgeServerLogTag,
            "BridgeServer.start() failed after ${attempted.size} attempts: " +
                "tried ${attempted.joinToString(", ")}",
            lastError,
        )
        return false
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        resolvedName = null
        scope.cancel()
    }

    internal suspend fun handleRequestForTest(payload: String): String = handleRequest(payload)

    private suspend fun acceptLoop(socket: LocalServerSocket) {
        while (scope.isActive) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                break
            }
            scope.launch {
                try {
                    handleClient(client)
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    private suspend fun handleClient(client: LocalSocket) {
        val input = client.inputStream
        val output = client.outputStream
        while (scope.isActive) {
            val request = try {
                BridgeProtocol.readFrame(input) ?: return
            } catch (_: Throwable) {
                return
            }
            val response = handleRequest(request)
            BridgeProtocol.writeFrame(output, response)
        }
    }

    private suspend fun handleRequest(payload: String): String {
        val request = runCatching {
            BridgeProtocol.json.decodeFromString(BridgeRequest.serializer(), payload)
        }.getOrElse {
            return BridgeProtocol.error(id = null, code = "BAD_REQUEST", message = "Invalid bridge request JSON")
        }

        if (request.token != session.token) {
            return BridgeProtocol.error(request.id, "UNAUTHORIZED", "Missing or mismatched FixThis bridge token")
        }
        return try {
            val result = when (request.method) {
                "heartbeat" -> {
                    connectionState.markAuthorizedRequest()
                    BridgeProtocol.json.encodeToJsonElement(BridgeHeartbeatResult())
                }
                "status" -> BridgeProtocol.json.encodeToJsonElement(
                    environment.status().copy(socketName = resolvedName ?: session.socketName),
                )
                "inspectCurrentScreen" -> BridgeProtocol.json.encodeToJsonElement(environment.inspectCurrentScreen())
                "captureScreenSnapshot" -> BridgeProtocol.json.encodeToJsonElement(environment.captureScreenSnapshot())
                "readSourceIndex" -> BridgeProtocol.json.encodeToJsonElement(environment.readSourceIndex())
                "verifyUiChange" -> BridgeProtocol.json.encodeToJsonElement(verifyUiChange(request.params))
                "readScreenshot" -> BridgeProtocol.json.encodeToJsonElement(readScreenshot(request.params))
                "performNavigation" -> BridgeProtocol.json.encodeToJsonElement(
                    environment.performNavigation(
                        BridgeProtocol.json.decodeFromJsonElement(
                            BridgeNavigationRequest.serializer(),
                            request.params,
                        ),
                    ),
                )
                else -> return BridgeProtocol.error(request.id, "UNKNOWN_METHOD", "Unknown bridge method: ${request.method}")
            }
            BridgeProtocol.success(request.id, result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            BridgeProtocol.error(
                id = request.id,
                code = "METHOD_FAILED",
                message = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private suspend fun verifyUiChange(params: JsonObject): BridgeUiVerificationResult {
        val expectedText = params.stringParam("expectedText")?.takeIf { it.isNotBlank() }
            ?: return BridgeUiVerificationResult(
                verified = false,
                message = "No expectedText parameter was provided",
            )
        val inspection = environment.inspectCurrentScreen()
        val matched = inspection.roots
            .flatMap { it.mergedNodes + it.unmergedNodes }
            .flatMap { node -> node.text + node.contentDescription }
            .firstOrNull { value -> value.contains(expectedText, ignoreCase = true) }
        return BridgeUiVerificationResult(
            verified = matched != null,
            expectedText = expectedText,
            matchedText = matched,
            message = if (matched == null) "Expected text was not found on the current screen" else null,
        )
    }

    private suspend fun readScreenshot(params: JsonObject): BridgeScreenshotReadResult {
        require(params.stringParam("path") == null) {
            "Explicit screenshot paths are not supported; use kind=full or kind=crop for the latest screen snapshot"
        }
        val kind = params.stringParam("kind") ?: "full"
        require(kind == "full" || kind == "crop") { "Unsupported screenshot kind: $kind" }
        val source = params.stringParam("source") ?: "screenSnapshot"
        require(source == "screenSnapshot") { "Unsupported screenshot source: $source" }
        val screenshot = environment.getLastScreenSnapshot()?.screenshot
        val path = when (kind) {
            "crop" -> screenshot?.cropPath
            else -> screenshot?.fullPath
        }
        require(!path.isNullOrBlank()) { "No screenshot path is available" }
        return withContext(ioDispatcher) {
            val file = File(path).canonicalFile
            val cacheDirectory = environment.screenshotCacheDirectory().canonicalFile
            require(PathSafety.isUnder(file, cacheDirectory)) {
                "Screenshot path is outside the FixThis screenshot cache"
            }
            require(file.extension.equals("png", ignoreCase = true)) { "Screenshot must be a PNG file" }
            require(file.exists() && file.isFile) { "Screenshot does not exist: $path" }
            require(file.length() <= MaxScreenshotReadBytes) { "Screenshot is too large to read" }
            require(file.hasPngHeader()) { "Screenshot file is not PNG data" }
            BridgeScreenshotReadResult(
                path = file.absolutePath,
                kind = kind,
                mimeType = "image/png",
                base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP),
            )
        }
    }

    private companion object {
        const val MaxScreenshotReadBytes = 16L * 1024L * 1024L
        const val BridgeServerLogTag = "FixThisBridge"
    }
}

interface BridgeEnvironment {
    suspend fun status(): BridgeStatus
    suspend fun inspectCurrentScreen(): BridgeScreenInspection
    suspend fun captureScreenSnapshot(): BridgeScreenSnapshot
    suspend fun readSourceIndex(): BridgeSourceIndexResult
    suspend fun getLastScreenSnapshot(): BridgeScreenSnapshot?
    suspend fun performNavigation(request: BridgeNavigationRequest): BridgeNavigationResult
    fun screenshotCacheDirectory(): File
}

@Serializable
data class BridgeStatus(
    val activity: String? = null,
    val rootsCount: Int,
    val sidekickVersion: String,
    val bridgeProtocolVersion: String,
    val sourceIndexAvailable: Boolean,
    val capabilities: BridgeCapabilities = BridgeCapabilities(),
    val screenInteractive: Boolean? = null,
    val keyguardLocked: Boolean? = null,
    val appForeground: Boolean? = null,
    val pictureInPicture: Boolean? = null,
    /**
     * APK install/update timestamp in milliseconds (`PackageManager.lastUpdateTime`).
     * Populated by [AndroidBridgeEnvironment.readInstallEpochMillis]; null if read fails.
     * Used to detect "the user reinstalled the sample APK" (e.g. for cache invalidation),
     * NOT for build-binary staleness — for that, see [sidekickBuildEpochMs].
     */
    val installEpochMillis: Long? = null,
    /**
     * Sidekick BUILD timestamp in milliseconds (minute-rounded; from `BuildInfo.BUILD_EPOCH_MS`).
     * Populated unconditionally by sidekick when this version of the protocol is in effect.
     * Compared by the console's `checkSidekickBuildEpoch()` against the bundled
     * `ConsoleBuildEpochMs`; drift > 5 min surfaces a "sample sidekick is older than console"
     * staleness banner. NOT the install time — for that, see [installEpochMillis].
     */
    val sidekickBuildEpochMs: Long? = null,
    /**
     * The actual abstract-namespace socket name `BridgeServer` is bound to.
     * Equals `SessionTokenStore.socketNameForPackage(packageName)` in the happy
     * path, but may carry a `-1` / `-2` suffix if a stale prior binding forced
     * [BridgeSocketNameNegotiator] to retry. Hosts (CLI / MCP) should prefer
     * this field over the value baked into `session.json` when the two differ.
     */
    val socketName: String? = null,
) {
    constructor(
        activity: String?,
        rootsCount: Int,
        sidekickVersion: String,
        bridgeProtocolVersion: String,
        sourceIndexAvailable: Boolean,
    ) : this(
        activity = activity,
        rootsCount = rootsCount,
        sidekickVersion = sidekickVersion,
        bridgeProtocolVersion = bridgeProtocolVersion,
        sourceIndexAvailable = sourceIndexAvailable,
        capabilities = BridgeCapabilities(),
        screenInteractive = null,
        keyguardLocked = null,
        appForeground = null,
        pictureInPicture = null,
        installEpochMillis = null,
        sidekickBuildEpochMs = null,
    )
}

@Serializable
data class BridgeCapabilities(
    val targetEvidence: Boolean = true,
    val detailModes: List<String> = listOf("compact", "precise", "full"),
    val composableIdentity: Boolean = false,
)

@Serializable
data class BridgeScreenInspection(
    val activity: String? = null,
    val roots: List<BridgeInspectedRoot> = emptyList(),
    val errors: List<FixThisError> = emptyList(),
)

@Serializable
data class BridgeScreenSnapshot(
    val activity: String? = null,
    val inspection: BridgeScreenInspection,
    val screenshot: ScreenshotInfo? = null,
    val sourceIndexAvailable: Boolean = false,
    /**
     * Logical orientation reported by `Activity.resources.configuration.orientation`
     * at capture time. `"PORTRAIT"` or `"LANDSCAPE"`, or null when the platform
     * reports `ORIENTATION_UNDEFINED` (e.g. capture failed before an activity was
     * resumed). Encoded as a String on the wire to keep DTO serialization simple.
     */
    val orientation: String? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val densityDpi: Int? = null,
    /**
     * High-level windowing mode active at capture time: `"PIP"`, `"SPLIT_SCREEN"`,
     * or `"FULLSCREEN"` (default). Null only when no activity was available to
     * inspect. PIP takes precedence over multi-window.
     */
    val windowMode: String? = null,
    /** Reserved for SIF-4 (Task B.5); always null in this build. */
    val systemUiVisible: Boolean? = null,
    /** Reserved for SIF-4 (Task B.5); always null in this build. */
    val systemUiKind: String? = null,
    /**
     * 16-hex-char fingerprint computed from orientation / display metrics /
     * window mode / systemUiKind via core `SnapshotFingerprint.compute(...)`.
     * Left null in the sidekick today; downstream (`fixthis-mcp`) can compute it
     * from these populated fields when promoting the DTO into a core `Snapshot`.
     */
    val fingerprint: String? = null,
)

/**
 * Maps the integer constant from `Configuration.orientation` to the wire-DTO
 * string used by `BridgeScreenSnapshot.orientation`. Returns null for
 * `ORIENTATION_UNDEFINED` so downstream consumers can distinguish "unknown"
 * from "definitely portrait/landscape".
 */
internal fun mapOrientation(configurationOrientation: Int): String? = when (configurationOrientation) {
    Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
    Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
    else -> null
}

/**
 * Pure-function form of [inferWindowMode]. PIP wins over multi-window so a
 * picture-in-picture window that is technically also in multi-window mode is
 * still classified as `"PIP"`. Default is `"FULLSCREEN"`.
 */
internal fun mapWindowMode(isPip: Boolean, isMultiWindow: Boolean): String = when {
    isPip -> "PIP"
    isMultiWindow -> "SPLIT_SCREEN"
    else -> "FULLSCREEN"
}

/**
 * Inspects the activity for picture-in-picture / multi-window state and returns
 * the wire-DTO window mode string. Delegates the precedence logic to
 * [mapWindowMode] so the branch logic is unit-testable without an Activity.
 */
internal fun inferWindowMode(activity: Activity): String = mapWindowMode(
    isPip = activity.isInPictureInPictureMode,
    isMultiWindow = activity.isInMultiWindowMode,
)

@Serializable
data class BridgeSourceIndexResult(
    val sourceIndexAvailable: Boolean,
    val sourceIndex: SourceIndex? = null,
    val sourceIndexError: String? = null,
)

@Serializable
data class BridgeInspectedRoot(
    val rootIndex: Int,
    val boundsInWindow: FixThisRect,
    val mergedNodes: List<FixThisNode>,
    val unmergedNodes: List<FixThisNode>,
)

@Serializable
data class BridgeUiVerificationResult(
    val verified: Boolean,
    val expectedText: String? = null,
    val matchedText: String? = null,
    val message: String? = null,
)

@Serializable
data class BridgeScreenshotReadResult(
    val path: String,
    val kind: String,
    val mimeType: String,
    val base64: String,
)

@Serializable
data class BridgeHeartbeatResult(
    val connected: Boolean = true,
)

internal object FixThisBridgeRuntime {
    private val lock = Any()
    private var server: BridgeServer? = null
    private var environment: AndroidBridgeEnvironment? = null
    internal val connectionState = BridgeConnectionState()

    fun start(
        application: Application,
        lifecycleCallbacks: FixThisActivityLifecycleCallbacks,
    ): Boolean {
        if (!application.isDebuggable()) return false
        synchronized(lock) {
            if (server != null) return false
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
            if (!bridgeServer.start()) return false
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
            return true
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

internal class AndroidBridgeEnvironment(
    private val context: Context,
    private val sidekickVersion: String,
    private val lifecycleCallbacks: FixThisActivityLifecycleCallbacks,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val inspector: SemanticsInspector = SemanticsInspector(),
    private val screenshotCapturer: ScreenshotCapturer = ScreenshotCapturer(ScreenshotStore(context)),
) : BridgeEnvironment {
    var currentActivity: WeakReference<Activity>? = null
    private var lastScreenSnapshot: BridgeScreenSnapshot? = null

    @Volatile
    private var cachedSourceIndexResult: BridgeSourceIndexResult? = null
    private val navigationPerformer = AndroidNavigationPerformer(
        activityProvider = { currentActivity?.get() },
        mainDispatcher = mainDispatcher,
    )

    override suspend fun status(): BridgeStatus {
        val inspection = inspectCurrentScreen()
        val sourceIndexResult = readSourceIndex()
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
            pictureInPicture = resumedActivity?.isInPictureInPictureMode,
            installEpochMillis = readInstallEpochMillis(),
            sidekickBuildEpochMs = BuildInfo.BUILD_EPOCH_MS,
        )
    }

    private fun readInstallEpochMillis(): Long? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrNull()

    override suspend fun inspectCurrentScreen(): BridgeScreenInspection = withContext(mainDispatcher) {
        val activity = currentActivity?.get()
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

    override suspend fun captureScreenSnapshot(): BridgeScreenSnapshot = readSourceIndex().let { sourceIndexResult ->
        withContext(mainDispatcher) {
            val sourceIndexAvailable = sourceIndexResult.sourceIndexAvailable
            val activity = currentActivity?.get()
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

    override suspend fun performNavigation(request: BridgeNavigationRequest): BridgeNavigationResult = navigationPerformer.perform(request)

    override fun screenshotCacheDirectory(): File = File(context.cacheDir, "fixthis")
}

private fun Context.hasAsset(path: String): Boolean = runCatching {
    assets.open(path).use { true }
}.getOrDefault(false)

private fun Context.readSourceIndexResult(path: String): BridgeSourceIndexResult {
    if (!hasAsset(path)) {
        return BridgeSourceIndexResult(sourceIndexAvailable = false)
    }
    val json = runCatching {
        assets.open(path).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MaxSourceIndexAssetBytes) {
                    return BridgeSourceIndexResult(
                        sourceIndexAvailable = false,
                        sourceIndexError = "Source index asset exceeds $MaxSourceIndexAssetBytes bytes",
                    )
                }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }.getOrElse { error ->
        return BridgeSourceIndexResult(
            sourceIndexAvailable = false,
            sourceIndexError = error.message ?: error::class.java.simpleName,
        )
    }
    return runCatching {
        BridgeSourceIndexResult(
            sourceIndexAvailable = true,
            sourceIndex = BridgeProtocol.json.decodeFromString(SourceIndex.serializer(), json),
        )
    }.getOrElse { error ->
        BridgeSourceIndexResult(
            sourceIndexAvailable = false,
            sourceIndexError = error.message ?: error::class.java.simpleName,
        )
    }
}

private const val MaxSourceIndexAssetBytes = 4 * 1024 * 1024

private fun Application.isDebuggable(): Boolean = runCatching {
    applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}.getOrDefault(false)

private val BridgePngHeader: ByteArray = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4E,
    0x47,
    0x0D,
    0x0A,
    0x1A,
    0x0A,
)

private fun File.hasPngHeader(): Boolean {
    if (length() < BridgePngHeader.size) return false
    return inputStream().use { input ->
        val header = ByteArray(BridgePngHeader.size)
        input.read(header) == BridgePngHeader.size && header.contentEquals(BridgePngHeader)
    }
}

private fun JsonObject.stringParam(name: String): String? = get(name)?.jsonPrimitive?.content
