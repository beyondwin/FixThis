package io.github.beyondwin.fixthis.cli.readiness

import kotlinx.serialization.Serializable

@Serializable
enum class FirstRunReadinessState {
    READY,
    NEEDS_INSTALL,
    NEEDS_APP_LAUNCH,
    DEVICE_BLOCKED,
    UNSUPPORTED_BUILD,
    CONFIG_RECOVERABLE,
    ENV_BLOCKER,
    STALE_PREVIEW,
    SESSION_MISMATCH,
    CAPTURE_UNAVAILABLE,
    UNKNOWN_ERROR,
}

@Serializable
data class FirstRunReadiness(
    val state: FirstRunReadinessState,
    val cause: String,
    val verify: String,
    val fix: String,
    val nextAction: String,
    val details: Map<String, String> = emptyMap(),
)

object FirstRunReadinessCatalog {
    fun ready(details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.READY,
        cause = "Debug app and FixThis sidekick are connected.",
        verify = "Capture a screen from the feedback console.",
        fix = "No recovery action required.",
        nextAction = "Capture screen",
        details = details,
    )

    fun needsInstall(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.NEEDS_INSTALL,
        cause = cause,
        verify = "./gradlew fixthisSetup",
        fix = "Run `fixthis install-agent --project-dir . --target all` from the Android project root.",
        nextAction = "Run `fixthis install-agent --project-dir . --target all`",
        details = details,
    )

    fun needsAppLaunch(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.NEEDS_APP_LAUNCH,
        cause = cause,
        verify = "fixthis doctor --project-dir . --json",
        fix = "Launch the debug app so the FixThis sidekick can write its bridge session.",
        nextAction = "Open app",
        details = details,
    )

    fun deviceBlocked(cause: String, fix: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.DEVICE_BLOCKED,
        cause = cause,
        verify = "Check the device state shown in the feedback console.",
        fix = fix,
        nextAction = fix,
        details = details,
    )

    fun unsupportedBuild(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.UNSUPPORTED_BUILD,
        cause = cause,
        verify = "adb shell run-as <applicationId> ls files/fixthis/session.json",
        fix = "Install a debuggable build with the FixThis sidekick enabled.",
        nextAction = "Install a debuggable build with FixThis enabled.",
        details = details,
    )

    fun configRecoverable(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.CONFIG_RECOVERABLE,
        cause = cause,
        verify = "fixthis install-agent --project-dir . --target all --dry-run",
        fix = "Re-run FixThis setup or inspect the dry-run diff before writing config.",
        nextAction = "Run `fixthis install-agent --project-dir . --target all --dry-run`",
        details = details,
    )

    fun envBlocker(cause: String, fix: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.ENV_BLOCKER,
        cause = cause,
        verify = "fixthis doctor --project-dir . --json",
        fix = fix,
        nextAction = fix,
        details = details,
    )

    fun stalePreview(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.STALE_PREVIEW,
        cause = cause,
        verify = "Compare the frozen preview with the current app screen.",
        fix = "Recapture, force-save the frozen preview, or cancel.",
        nextAction = "Choose Recapture, Force save, or Cancel.",
        details = details,
    )
}

object FirstRunReadinessFailureCatalog {
    fun captureUnavailable(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.CAPTURE_UNAVAILABLE,
        cause = cause,
        verify = "Open the app foreground and tap Capture, or open doctor for the bridge log.",
        fix = "Reopen the app foreground and tap Retry capture, or open doctor for the bridge log.",
        nextAction = "Retry capture",
        details = details,
    )

    fun sessionMismatch(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.SESSION_MISMATCH,
        cause = cause,
        verify = "Compare the response sessionId with the active feedback session.",
        fix = "Refresh the active session or return to the matching history item.",
        nextAction = "Refresh session",
        details = details,
    )

    fun unknown(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.UNKNOWN_ERROR,
        cause = cause,
        verify = "fixthis doctor --project-dir . --json",
        fix = "Open diagnostic details and rerun the failed command with --verbose.",
        nextAction = "Open diagnostic details",
        details = details,
    )
}

fun classifyBridgeFailure(rawError: String?): FirstRunReadiness {
    val raw = rawError.orEmpty()
    val lower = raw.lowercase()
    return when {
        "not debuggable" in lower ||
            ("run-as" in lower && "permission" in lower) ||
            ("run-as" in lower && "denied" in lower) ->
            FirstRunReadinessCatalog.unsupportedBuild(
                cause = "This build cannot expose the FixThis bridge.",
                details = mapOf("rawError" to raw),
            )
        "could not connect to fixthis bridge" in lower ||
            "bridge closed before sending a response" in lower ||
            ("bridge" in lower && "timed out" in lower) ->
            FirstRunReadinessCatalog.needsAppLaunch(
                cause = "The debug app is not connected to the FixThis bridge.",
                details = mapOf("rawError" to raw),
            )
        else -> FirstRunReadinessFailureCatalog.unknown(
            cause = raw.ifBlank { "FixThis could not classify this first-run failure." },
            details = if (raw.isBlank()) emptyMap() else mapOf("rawError" to raw),
        )
    }
}
