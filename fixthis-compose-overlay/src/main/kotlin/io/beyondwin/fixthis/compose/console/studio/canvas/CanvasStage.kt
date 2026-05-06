package io.beyondwin.fixthis.compose.console.studio.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.compose.console.studio.StudioViewModel
import io.beyondwin.fixthis.compose.console.studio.theme.StudioColors

@Composable
internal fun CanvasStage(
    vm: StudioViewModel,
    previewState: FullPreviewDecodeState,
    interactionMode: CanvasInteractionMode,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(stageBackground())
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        PhoneFrame {
            PreviewSurface(
                vm = vm,
                previewState = previewState,
                interactionMode = interactionMode,
            )
        }
    }
}

private fun stageBackground(): Brush =
    Brush.radialGradient(
        colors = listOf(StudioColors.Bg1, StudioColors.Bg0),
        center = Offset.Unspecified,
        radius = 1500f,
    )
