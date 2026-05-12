package io.beyondwin.fixthis.compose.sidekick.bridge

import android.app.Activity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * SIF-4 (Task B.5): system-UI obstruction detector.
 *
 * Maps the runtime UI state — IME insets and (optionally) an adb `dumpsys window
 * windows | grep mCurrentFocus` line — into a `systemUiKind` string consumed by
 * `BridgeScreenSnapshot`. The classification is intentionally split into two pure
 * helpers ([detectImeKind] and [detectFocusKind]) so branch logic is unit-testable
 * without Robolectric; only [detect] requires an activity reference.
 *
 * IME wins over focus output when both fire — a soft keyboard overlay is the
 * stronger signal because it physically obscures the active screen, whereas the
 * focus output may lag the foreground transition.
 *
 * The adb sideband (`currentFocusOutput`) is fed in by `fixthis-mcp` / `fixthis-cli`
 * in a later phase; the sidekick passes null today.
 */
internal object SystemUiDetector {
    /**
     * Inspect [activity] for IME insets, then fall back to the supplied
     * [currentFocusOutput] line (a single `mCurrentFocus=...` line from
     * `adb shell dumpsys window windows`). Returns:
     *
     * - `"ime"` when IME insets are visible on the decor view.
     * - `"permission_dialog"` when the focus mentions either the AOSP or
     *   GMS PermissionController package.
     * - `"notification_shade"` when the focus mentions the system StatusBar.
     * - `null` otherwise.
     */
    fun detect(activity: Activity, currentFocusOutput: String?): String? {
        val imeVisible = runCatching {
            val decor = activity.window?.decorView ?: return@runCatching false
            ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
        }.getOrDefault(false)
        return mapSystemUiKind(imeInsetsVisible = imeVisible, currentFocusOutput = currentFocusOutput)
    }
}

/**
 * Pure-function form of the IME branch. Returns `"ime"` when the platform reports
 * the IME inset as visible; null otherwise.
 */
internal fun detectImeKind(insetsVisible: Boolean): String? = if (insetsVisible) "ime" else null

/**
 * Pure-function form of the focus-output branch. Parses a single
 * `mCurrentFocus=...` line emitted by `adb shell dumpsys window windows` and maps
 * it to a `systemUiKind`. Returns null for empty or unrecognised input.
 *
 * Permission UI is matched on the substring `permissioncontroller` so that both
 * the AOSP (`com.android.permissioncontroller`) and GMS
 * (`com.google.android.permissioncontroller`) variants classify the same.
 *
 * The notification shade is matched on `StatusBar` (case-sensitive, matching the
 * dumpsys window-name convention).
 */
internal fun detectFocusKind(currentFocusOutput: String?): String? {
    val output = currentFocusOutput?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        output.contains("permissioncontroller") -> "permission_dialog"
        output.contains("StatusBar") -> "notification_shade"
        else -> null
    }
}

/**
 * Composes [detectImeKind] and [detectFocusKind] with IME-wins precedence.
 * Surfaced for unit tests; the activity-bound entry point is [SystemUiDetector.detect].
 */
internal fun mapSystemUiKind(
    imeInsetsVisible: Boolean,
    currentFocusOutput: String?,
): String? = detectImeKind(imeInsetsVisible) ?: detectFocusKind(currentFocusOutput)
