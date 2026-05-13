package io.beyondwin.fixthis.compose.sidekick.bridge

import android.app.Activity
import android.content.res.Configuration
import io.beyondwin.fixthis.compose.core.model.FixThisError
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.ScreenshotInfo
import io.beyondwin.fixthis.compose.core.source.SourceIndex
import kotlinx.serialization.Serializable

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
     * NOT for build-binary staleness - for that, see [sidekickBuildEpochMs].
     */
    val installEpochMillis: Long? = null,
    /**
     * Sidekick BUILD timestamp in milliseconds (minute-rounded; from `BuildInfo.BUILD_EPOCH_MS`).
     * Populated unconditionally by sidekick when this version of the protocol is in effect.
     * Compared by the console's `checkSidekickBuildEpoch()` against the bundled
     * `ConsoleBuildEpochMs`; drift > 5 min surfaces a "sample sidekick is older than console"
     * staleness banner. NOT the install time - for that, see [installEpochMillis].
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
    /**
     * SIF-4 (Task B.5): true when [systemUiKind] is non-null, i.e. an obstructing
     * system surface (IME, permission dialog, notification shade) was detected at
     * capture time. Derived field - equals `systemUiKind != null`.
     */
    val systemUiVisible: Boolean? = null,
    /**
     * SIF-4 (Task B.5): classification of the obstructing system surface, one of
     * `"ime"`, `"permission_dialog"`, `"notification_shade"`, or null when no
     * obstruction was detected. The adb-derived focus sideband is wired in a later
     * phase; today only IME insets feed this field.
     */
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
