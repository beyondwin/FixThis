package io.github.beyondwin.fixthis.compose.core.domain.snapshot

/**
 * Windowing mode active when a [Snapshot] was captured.
 *
 * Tracks the high-level multi-window state so the lifecycle pipeline can detect
 * window-mode transitions that affect annotation validity.
 */
enum class WindowMode {
    FULLSCREEN,
    SPLIT_SCREEN,
    FREEFORM,
    PIP,
}
