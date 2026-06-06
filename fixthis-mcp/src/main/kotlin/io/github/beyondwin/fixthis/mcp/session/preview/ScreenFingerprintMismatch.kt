package io.github.beyondwin.fixthis.mcp.session.preview

/**
 * Thrown by [FeedbackDraftService.savePreviewFeedbackItems] when the frozen
 * screen fingerprint captured at preview-freeze time differs from the current
 * screen fingerprint observed at save time, and the caller has not opted in to
 * [forceMismatchOverride].
 *
 * Routes should translate this to HTTP 409 Conflict with a JSON body containing
 * both fingerprints so the console can prompt the user (re-capture, force save,
 * or cancel).
 */
class ScreenFingerprintMismatch(
    val frozenFingerprint: String,
    val currentFingerprint: String,
) : RuntimeException(
    "Screen fingerprint mismatch: frozen=$frozenFingerprint, current=$currentFingerprint",
)
