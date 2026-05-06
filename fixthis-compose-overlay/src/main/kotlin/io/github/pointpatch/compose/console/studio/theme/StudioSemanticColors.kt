package io.github.pointpatch.compose.console.studio.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
internal data class StudioSemanticColors(
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceHigher: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val onSurfaceSubtle: Color,
    val border: Color,
    val borderSubtle: Color,
    val accent: Color,
    val accentPressed: Color,
    val danger: Color,
    val warning: Color,
    val statusOpen: Color,
    val statusInProgress: Color,
    val statusResolved: Color,
)

internal fun darkStudioSemanticColors(): StudioSemanticColors =
    StudioSemanticColors(
        surface = StudioColors.Bg0,
        surfaceRaised = StudioColors.Bg1,
        surfaceHigher = StudioColors.Bg2,
        onSurface = StudioColors.Txt0,
        onSurfaceMuted = StudioColors.Txt1,
        onSurfaceSubtle = StudioColors.Txt2,
        border = StudioColors.Line,
        borderSubtle = StudioColors.LineSoft,
        accent = StudioColors.Accent,
        accentPressed = StudioColors.AccentDeep,
        danger = StudioColors.Danger,
        warning = StudioColors.Warn,
        statusOpen = StudioColors.StatusOpen,
        statusInProgress = StudioColors.StatusInProgress,
        statusResolved = StudioColors.StatusResolved,
    )
