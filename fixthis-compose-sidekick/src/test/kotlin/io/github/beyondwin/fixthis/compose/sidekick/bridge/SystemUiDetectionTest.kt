package io.github.beyondwin.fixthis.compose.sidekick.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SIF-4 (Task B.5): pure-function tests for [SystemUiDetector] mapping logic.
 *
 * The detector composes two pure helpers — [detectImeKind] (insets-derived) and
 * [detectFocusKind] (adb sideband-derived) — so the branch logic stays unit-testable
 * without Robolectric. The activity-bound [SystemUiDetector.detect] entry point is
 * exercised via integration once the adb sideband lands (Phase E RTI-1).
 */
class SystemUiDetectionTest {
    @Test
    fun `detectFocusKind returns permission_dialog for android permissioncontroller focus`() {
        val focus = "  mCurrentFocus=Window{abc u0 com.android.permissioncontroller/...PermissionActivity}"
        assertEquals("permission_dialog", detectFocusKind(focus))
    }

    @Test
    fun `detectFocusKind returns permission_dialog for google android permissioncontroller focus`() {
        val focus = "  mCurrentFocus=Window{def u0 com.google.android.permissioncontroller/...GrantActivity}"
        assertEquals("permission_dialog", detectFocusKind(focus))
    }

    @Test
    fun `detectFocusKind returns notification_shade for StatusBar focus`() {
        val focus = "  mCurrentFocus=Window{xyz u0 StatusBar}"
        assertEquals("notification_shade", detectFocusKind(focus))
    }

    @Test
    fun `detectFocusKind returns null for empty input`() {
        assertNull(detectFocusKind(""))
        assertNull(detectFocusKind(null))
    }

    @Test
    fun `detectFocusKind returns null for unmatched focus output`() {
        val focus = "  mCurrentFocus=Window{123 u0 com.example.app/com.example.app.MainActivity}"
        assertNull(detectFocusKind(focus))
    }

    @Test
    fun `detectImeKind returns ime when insets visible`() {
        assertEquals("ime", detectImeKind(insetsVisible = true))
    }

    @Test
    fun `detectImeKind returns null when insets not visible`() {
        assertNull(detectImeKind(insetsVisible = false))
    }

    @Test
    fun `mapSystemUiKind prioritizes ime over focus output`() {
        // Even when focus mentions permissioncontroller, IME wins.
        val focus = "  mCurrentFocus=Window{abc u0 com.android.permissioncontroller/...PermissionActivity}"
        assertEquals("ime", mapSystemUiKind(imeInsetsVisible = true, currentFocusOutput = focus))
    }

    @Test
    fun `mapSystemUiKind falls back to focus when ime not visible`() {
        val focus = "  mCurrentFocus=Window{xyz u0 StatusBar}"
        assertEquals("notification_shade", mapSystemUiKind(imeInsetsVisible = false, currentFocusOutput = focus))
    }

    @Test
    fun `mapSystemUiKind returns null when neither ime nor focus matches`() {
        assertNull(mapSystemUiKind(imeInsetsVisible = false, currentFocusOutput = null))
        assertNull(mapSystemUiKind(imeInsetsVisible = false, currentFocusOutput = "unrelated text"))
    }
}
