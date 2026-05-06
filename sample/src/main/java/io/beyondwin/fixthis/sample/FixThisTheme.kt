package io.beyondwin.fixthis.sample

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object FixThisColors {
    val Accent = Color(0xff087f6f)
    val AccentSoft = Color(0xffd7f3ee)
    val Surface = Color(0xffffffff)
    val Background = Color(0xfff6f8f7)
    val TextPrimary = Color(0xff17201d)
    val TextSecondary = Color(0xff5f6f69)
    val Warning = Color(0xffb76e00)
    val WarningSoft = Color(0xffffefd1)
    val Critical = Color(0xffb42318)
    val CriticalSoft = Color(0xffffe3df)
    val Success = Color(0xff1b7f3a)
    val SuccessSoft = Color(0xffdff6e7)
    val Blocked = Color(0xff626970)
    val BlockedSoft = Color(0xffeceff1)
}

private val FixThisLightColorScheme: ColorScheme = lightColorScheme(
    primary = FixThisColors.Accent,
    onPrimary = Color.White,
    primaryContainer = FixThisColors.AccentSoft,
    onPrimaryContainer = FixThisColors.TextPrimary,
    secondary = Color(0xff47645e),
    onSecondary = Color.White,
    background = FixThisColors.Background,
    onBackground = FixThisColors.TextPrimary,
    surface = FixThisColors.Surface,
    onSurface = FixThisColors.TextPrimary,
    surfaceVariant = Color(0xffe3ebe7),
    onSurfaceVariant = FixThisColors.TextSecondary,
    error = FixThisColors.Critical,
    onError = Color.White,
)

@Composable
fun FixThisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FixThisLightColorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
