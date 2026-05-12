package io.beyondwin.fixthis.compose.core.domain.snapshot

/**
 * Logical screen orientation captured alongside a [Snapshot].
 *
 * Mirrors the four rotation states reported by the platform display so downstream
 * consumers can disambiguate landscape/portrait variants when correlating snapshots
 * with annotations.
 */
enum class ScreenOrientation {
    PORTRAIT,
    LANDSCAPE,
    REVERSE_PORTRAIT,
    REVERSE_LANDSCAPE,
}
