package io.github.pointpatch.compose.console.studio.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalStudioSemanticColors = staticCompositionLocalOf { darkStudioSemanticColors() }
private val LocalStudioSpacing = staticCompositionLocalOf { StudioSpacing() }
private val LocalStudioShapes = staticCompositionLocalOf { StudioShapes() }
private val LocalStudioElevation = staticCompositionLocalOf { StudioElevation() }

@Composable
internal fun StudioTheme(
    colors: StudioSemanticColors = darkStudioSemanticColors(),
    spacing: StudioSpacing = StudioSpacing(),
    shapes: StudioShapes = StudioShapes(),
    elevation: StudioElevation = StudioElevation(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalStudioSemanticColors provides colors,
        LocalStudioSpacing provides spacing,
        LocalStudioShapes provides shapes,
        LocalStudioElevation provides elevation,
    ) {
        content()
    }
}

internal object StudioThemeTokens {
    val colors: StudioSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioSemanticColors.current

    val spacing: StudioSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioSpacing.current

    val shapes: StudioShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioShapes.current

    val elevation: StudioElevation
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioElevation.current
}
