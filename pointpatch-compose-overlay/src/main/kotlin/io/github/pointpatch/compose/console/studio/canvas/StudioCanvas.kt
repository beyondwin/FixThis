package io.github.pointpatch.compose.console.studio.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import io.github.pointpatch.compose.console.studio.StudioViewModel
import io.github.pointpatch.compose.console.studio.theme.StudioColors
import io.github.pointpatch.compose.core.model.ScreenshotInfo

@Composable
internal fun StudioCanvas(
    vm: StudioViewModel,
    previewScreenshot: ScreenshotInfo?,
    modifier: Modifier = Modifier,
) {
    val previewPaths = previewScreenshot.fullPreviewPathCandidates()
    val hasScreenshotMetadata = previewScreenshot != null
    val previewState by produceState<FullPreviewDecodeState>(
        initialValue = initialFullPreviewDecodeState(
            paths = previewPaths,
            hasScreenshotMetadata = hasScreenshotMetadata,
        ),
        previewPaths,
        hasScreenshotMetadata,
    ) {
        value = initialFullPreviewDecodeState(
            paths = previewPaths,
            hasScreenshotMetadata = hasScreenshotMetadata,
        )
        val terminalState = loadFullPreview(
            paths = previewPaths,
            hasScreenshotMetadata = hasScreenshotMetadata,
        ) { success ->
            value = success
        }
        if (terminalState != null) {
            value = terminalState
        }
    }
    DisposableEffect(previewState) {
        onDispose {
            previewState.recycleSuccessBitmap()
        }
    }
    val interactionMode = previewInteractionMode(previewState.decodeStatus)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(StudioColors.Bg0),
    ) {
        CanvasToolbar(
            tool = vm.tool,
            onToolChange = vm::setTool,
            openCount = vm.openCount,
            resolvedCount = vm.resolvedCount,
            interactionMode = interactionMode,
        )
        CanvasStage(
            vm = vm,
            previewState = previewState,
            interactionMode = interactionMode,
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
        )
    }
}
