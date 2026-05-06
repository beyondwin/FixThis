package io.beyondwin.fixthis.compose.console.studio.common

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.beyondwin.fixthis.compose.console.studio.theme.StudioColors
import io.beyondwin.fixthis.compose.overlay.R

internal val StudioFontFamily: FontFamily = FontFamily(
    Font(R.font.inter, weight = FontWeight.Normal),
    Font(R.font.inter, weight = FontWeight.Medium),
    Font(R.font.inter, weight = FontWeight.SemiBold),
    Font(R.font.inter, weight = FontWeight.Bold),
)

internal object StudioType {
    val Base = TextStyle(fontFamily = StudioFontFamily, fontSize = 13.sp, color = StudioColors.Txt0)
    val PanelTitle = TextStyle(
        fontFamily = StudioFontFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.14.em,
        color = StudioColors.Txt2,
    )
    val PanelCount = TextStyle(
        fontFamily = StudioFontFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
        color = StudioColors.Txt1,
    )
    val FieldLabel = TextStyle(
        fontFamily = StudioFontFamily,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.14.em,
        color = StudioColors.Txt2,
    )
    val HiTitle = TextStyle(
        fontFamily = StudioFontFamily,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.9.sp,
        color = StudioColors.Txt0,
    )
    val AnnRowTitle = TextStyle(
        fontFamily = StudioFontFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = StudioColors.Txt0,
    )
    val AnnRowComment = TextStyle(
        fontFamily = StudioFontFamily,
        fontSize = 11.sp,
        lineHeight = 15.4.sp,
        color = StudioColors.Txt1,
    )
}
