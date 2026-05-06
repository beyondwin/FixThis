package io.github.pointpatch.compose.console.studio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.pointpatch.compose.console.studio.common.StudioFontFamily
import io.github.pointpatch.compose.console.studio.common.StudioType
import io.github.pointpatch.compose.console.studio.model.AnnotationStatus
import io.github.pointpatch.compose.console.studio.model.Severity
import io.github.pointpatch.compose.console.studio.model.wireValue
import io.github.pointpatch.compose.console.studio.theme.StudioColors
import io.github.pointpatch.compose.console.studio.theme.StudioSpacing
import io.github.pointpatch.compose.console.studio.theme.darkStudioSemanticColors
import io.github.pointpatch.compose.console.studio.theme.severityColor
import io.github.pointpatch.compose.console.studio.theme.statusDotColor
import io.github.pointpatch.compose.console.studio.theme.statusPillBg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioModelAndThemeTest {
    @Test
    fun modelEnumsExposeServerWireValues() {
        assertEquals("high", Severity.HIGH.wireValue())
        assertEquals("med", Severity.MED.wireValue())
        assertEquals("low", Severity.LOW.wireValue())

        assertEquals("open", AnnotationStatus.OPEN.wireValue())
        assertEquals("in-progress", AnnotationStatus.IN_PROGRESS.wireValue())
        assertEquals("resolved", AnnotationStatus.RESOLVED.wireValue())
    }

    @Test
    fun studioColorTokensMatchOptionAHexValues() {
        assertColor("#0D0E10", StudioColors.Bg0)
        assertColor("#131418", StudioColors.Bg1)
        assertColor("#1A1C21", StudioColors.Bg2)
        assertColor("#21242B", StudioColors.Bg3)
        assertColor("#2A2D35", StudioColors.Line)
        assertColor("#1F2228", StudioColors.LineSoft)
        assertColor("#E8E9EB", StudioColors.Txt0)
        assertColor("#B6B8BE", StudioColors.Txt1)
        assertColor("#7D8089", StudioColors.Txt2)
        assertColor("#B8D36A", StudioColors.Accent)
        assertColor("#8EAA49", StudioColors.AccentDeep)
        assertColor("#C8E07C", StudioColors.PrimaryHover)
        assertColor("#C8E07C", StudioColors.AccentHover)
        assertColor("#E6B45A", StudioColors.Warn)
        assertColor("#F26D6D", StudioColors.Danger)
        assertColor("#999999", StudioColors.MutedPlaceholder)
    }

    @Test
    fun studioSpacingTokensExposeStableDpValues() {
        val spacing = StudioSpacing()

        assertEquals(4.dp, spacing.xs)
        assertEquals(8.dp, spacing.sm)
        assertEquals(12.dp, spacing.md)
        assertEquals(16.dp, spacing.lg)
        assertEquals(24.dp, spacing.xl)
        assertEquals(32.dp, spacing.xxl)
    }

    @Test
    fun studioSemanticColorsMapExistingPalette() {
        val colors = darkStudioSemanticColors()

        assertColor("#0D0E10", colors.surface)
        assertColor("#131418", colors.surfaceRaised)
        assertColor("#E8E9EB", colors.onSurface)
        assertColor("#B6B8BE", colors.onSurfaceMuted)
        assertColor("#2A2D35", colors.border)
        assertColor("#B8D36A", colors.accent)
        assertColor("#F26D6D", colors.danger)
    }

    @Test
    fun semanticColorMappingsMatchOptionAStateColors() {
        assertColor("#F26D6D", severityColor(Severity.HIGH))
        assertColor("#E6B45A", severityColor(Severity.MED))
        assertColor("#5AB1E6", severityColor(Severity.LOW))

        assertColor("#F2C94C", statusDotColor(AnnotationStatus.OPEN))
        assertColor("#5BB1E6", statusDotColor(AnnotationStatus.IN_PROGRESS))
        assertColor("#6FCF97", statusDotColor(AnnotationStatus.RESOLVED))

        assertColor("#F2C94C", statusPillBg(AnnotationStatus.OPEN).copy(alpha = 1f))
        assertColor("#5BB1E6", statusPillBg(AnnotationStatus.IN_PROGRESS).copy(alpha = 1f))
        assertColor("#6FCF97", statusPillBg(AnnotationStatus.RESOLVED).copy(alpha = 1f))
        assertEquals(0.15f, statusPillBg(AnnotationStatus.OPEN).alpha, 0.001f)
        assertEquals(0.15f, statusPillBg(AnnotationStatus.IN_PROGRESS).alpha, 0.001f)
        assertEquals(0.15f, statusPillBg(AnnotationStatus.RESOLVED).alpha, 0.001f)
    }

    @Test
    fun interactionOverlayColorTokensMatchOptionAAlphaContracts() {
        assertColor("#B8D36A", StudioColors.AnnotateHintBg.copy(alpha = 1f))
        assertAlpha(0.08f, StudioColors.AnnotateHintBg)
        assertColor("#B8D36A", StudioColors.AnnotateHintBorder.copy(alpha = 1f))
        assertAlpha(0.25f, StudioColors.AnnotateHintBorder)

        assertColor("#78B4FF", StudioColors.DragRectFill.copy(alpha = 1f))
        assertAlpha(0.12f, StudioColors.DragRectFill)
        assertColor("#0D0E10", StudioColors.DragRectBorder.copy(alpha = 1f))
        assertAlpha(0.60f, StudioColors.DragRectBorder)

        assertColor("#6B4EFF", StudioColors.WidgetHoverOutline.copy(alpha = 1f))
        assertAlpha(0.70f, StudioColors.WidgetHoverOutline)
    }

    @Test
    fun studioTypographyTokensMatchOptionASpec() {
        assertEquals(13.sp, StudioType.Base.fontSize)
        assertColor("#E8E9EB", StudioType.Base.color)
        assertEquals(StudioFontFamily, StudioType.Base.fontFamily)
        assertTrue(StudioFontFamily != FontFamily.Default)

        assertEquals(11.sp, StudioType.PanelTitle.fontSize)
        assertEquals(FontWeight.SemiBold, StudioType.PanelTitle.fontWeight)
        assertEquals(0.14.em, StudioType.PanelTitle.letterSpacing)
        assertColor("#7D8089", StudioType.PanelTitle.color)

        assertEquals(10.sp, StudioType.FieldLabel.fontSize)
        assertEquals(FontWeight.SemiBold, StudioType.FieldLabel.fontWeight)
        assertEquals(0.14.em, StudioType.FieldLabel.letterSpacing)
        assertColor("#7D8089", StudioType.FieldLabel.color)

        assertEquals(12.sp, StudioType.AnnRowTitle.fontSize)
        assertEquals(FontWeight.SemiBold, StudioType.AnnRowTitle.fontWeight)
        assertColor("#E8E9EB", StudioType.AnnRowTitle.color)

        assertEquals(11.sp, StudioType.AnnRowComment.fontSize)
        assertEquals(15.4.sp, StudioType.AnnRowComment.lineHeight)
        assertColor("#B6B8BE", StudioType.AnnRowComment.color)
    }
}

private fun assertColor(expectedHex: String, actual: Color) {
    val rgb = expectedHex.removePrefix("#").toLong(16)
    val expectedArgb = (0xFF000000L or rgb).toInt()
    assertEquals("Expected $expectedHex", expectedArgb, actual.toArgb())
}

private fun assertAlpha(expected: Float, actual: Color) {
    assertEquals(expected, actual.alpha, 1f / 255f)
}
