package io.beyondwin.fixthis.compose.console.studio.theme

import androidx.compose.ui.graphics.Color
import io.beyondwin.fixthis.compose.console.studio.model.AnnotationStatus
import io.beyondwin.fixthis.compose.console.studio.model.Severity

internal object StudioColors {
    val Bg0 = Color(0xFF0D0E10)
    val Bg1 = Color(0xFF131418)
    val Bg2 = Color(0xFF1A1C21)
    val Bg3 = Color(0xFF21242B)
    val Line = Color(0xFF2A2D35)
    val LineSoft = Color(0xFF1F2228)
    val Txt0 = Color(0xFFE8E9EB)
    val Txt1 = Color(0xFFB6B8BE)
    val Txt2 = Color(0xFF7D8089)
    val Accent = Color(0xFFB8D36A)
    val AccentDeep = Color(0xFF8EAA49)
    val PrimaryHover = Color(0xFFC8E07C)
    val AccentHover = PrimaryHover
    val Warn = Color(0xFFE6B45A)
    val Danger = Color(0xFFF26D6D)
    val MutedPlaceholder = Color(0xFF999999)

    val SeverityHigh = Color(0xFFF26D6D)
    val SeverityMed = Color(0xFFE6B45A)
    val SeverityLow = Color(0xFF5AB1E6)

    val StatusOpen = Color(0xFFF2C94C)
    val StatusResolved = Color(0xFF6FCF97)
    val StatusInProgress = Color(0xFF5BB1E6)

    val StatusOpenPillBg = StatusOpen.copy(alpha = 0.15f)
    val StatusResolvedPillBg = StatusResolved.copy(alpha = 0.15f)
    val StatusInProgressPillBg = StatusInProgress.copy(alpha = 0.15f)

    val AnnotateHintBg = Accent.copy(alpha = 0.08f)
    val AnnotateHintBorder = Accent.copy(alpha = 0.25f)
    val DragRectFill = Color(0xFF78B4FF).copy(alpha = 0.12f)
    val DragRectBorder = Bg0.copy(alpha = 0.6f)
    val WidgetHoverOutline = Color(0xFF6B4EFF).copy(alpha = 0.7f)
}

internal fun severityColor(severity: Severity): Color =
    when (severity) {
        Severity.HIGH -> StudioColors.SeverityHigh
        Severity.MED -> StudioColors.SeverityMed
        Severity.LOW -> StudioColors.SeverityLow
    }

internal fun statusDotColor(status: AnnotationStatus): Color =
    when (status) {
        AnnotationStatus.OPEN -> StudioColors.StatusOpen
        AnnotationStatus.IN_PROGRESS -> StudioColors.StatusInProgress
        AnnotationStatus.RESOLVED -> StudioColors.StatusResolved
    }

internal fun statusPillBg(status: AnnotationStatus): Color =
    when (status) {
        AnnotationStatus.OPEN -> StudioColors.StatusOpenPillBg
        AnnotationStatus.IN_PROGRESS -> StudioColors.StatusInProgressPillBg
        AnnotationStatus.RESOLVED -> StudioColors.StatusResolvedPillBg
    }
