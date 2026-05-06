package io.beyondwin.fixthis.sample

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object FixThisColors {
    val Accent = Color(0xff007c70)
    val AccentStrong = Color(0xff005f55)
    val AccentSoft = Color(0xffdff4ef)
    val Surface = Color(0xffffffff)
    val SurfaceRaised = Color(0xfffbfdfc)
    val Background = Color(0xfff5f8f6)
    val TextPrimary = Color(0xff15231f)
    val TextSecondary = Color(0xff5d7069)
    val Border = Color(0xffdfe8e4)
    val Warning = Color(0xff9a5c00)
    val WarningSoft = Color(0xfffff0d4)
    val Critical = Color(0xffa8291f)
    val CriticalSoft = Color(0xffffe3df)
    val Success = Color(0xff1b7f3a)
    val SuccessSoft = Color(0xffdff6e7)
    val Neutral = Color(0xff626970)
    val NeutralSoft = Color(0xffeceff1)
}

private val FixThisLightColorScheme: ColorScheme = lightColorScheme(
    primary = FixThisColors.Accent,
    onPrimary = Color.White,
    primaryContainer = FixThisColors.AccentSoft,
    onPrimaryContainer = FixThisColors.TextPrimary,
    secondary = Color(0xff48625a),
    onSecondary = Color.White,
    background = FixThisColors.Background,
    onBackground = FixThisColors.TextPrimary,
    surface = FixThisColors.Surface,
    onSurface = FixThisColors.TextPrimary,
    surfaceVariant = Color(0xffedf4f1),
    onSurfaceVariant = FixThisColors.TextSecondary,
    outline = FixThisColors.Border,
    error = FixThisColors.Critical,
    onError = Color.White,
)

private val FixThisTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun FixThisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FixThisLightColorScheme,
        typography = FixThisTypography,
        content = content,
    )
}
