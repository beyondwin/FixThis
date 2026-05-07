package io.beyondwin.fixthis.compose.console.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.beyondwin.fixthis.compose.console.studio.canvas.StudioCanvas
import io.beyondwin.fixthis.compose.console.studio.common.StudioFontFamily
import io.beyondwin.fixthis.compose.console.studio.history.StudioHistory
import io.beyondwin.fixthis.compose.console.studio.inspector.StudioInspector
import io.beyondwin.fixthis.compose.console.studio.theme.StudioColors
import io.beyondwin.fixthis.compose.console.studio.topbar.StudioTopbar
import io.beyondwin.fixthis.compose.core.model.ScreenshotInfo
import java.util.UUID

@Composable
fun FeedbackConsoleScreen(
    modifier: Modifier = Modifier,
    previewScreenshot: ScreenshotInfo? = null,
) {
    val instanceKey = rememberSaveable { UUID.randomUUID().toString() }
    val vm: StudioViewModel = viewModel(key = "fixthis-feedback-console:$instanceKey")
    StudioShell(
        vm = vm,
        previewScreenshot = previewScreenshot,
        modifier = modifier,
    )
}

@Composable
private fun StudioShell(
    vm: StudioViewModel,
    previewScreenshot: ScreenshotInfo?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioColors.Bg0),
    ) {
        StudioTopbar(
            draftTitle = vm.draftTitle,
            onDraftTitleChange = vm::setDraftTitle,
            canSave = vm.canSaveSnapshot,
            onSave = vm::saveSnapshot,
            onNewSession = vm::newSession,
            modifier = Modifier.height(StudioShellDefaults.TopbarHeight),
        )
        StudioBody(
            vm = vm,
            previewScreenshot = previewScreenshot,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StudioBody(
    vm: StudioViewModel,
    previewScreenshot: ScreenshotInfo?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth < StudioShellDefaults.MinimumBodyWidth) {
            ResizePlaceholder(modifier = Modifier.fillMaxSize())
            return@BoxWithConstraints
        }

        Row(modifier = Modifier.fillMaxSize()) {
            StudioHistory(
                snapshots = vm.snapshots,
                activeSnapId = vm.activeSnapId,
                onOpen = vm::openSnapshot,
                onDelete = vm::deleteSnapshot,
                modifier = Modifier
                    .width(StudioShellDefaults.HistoryWidth)
                    .fillMaxHeight(),
            )
            StudioCanvas(
                vm = vm,
                previewScreenshot = previewScreenshot,
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = StudioShellDefaults.CanvasMinWidth)
                    .fillMaxHeight(),
            )
            StudioInspector(
                vm = vm,
                modifier = Modifier
                    .width(StudioShellDefaults.InspectorWidth)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ResizePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(StudioColors.Bg0),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Resize to at least 1100dp wide",
            color = StudioColors.Txt1,
            fontSize = 13.sp,
            fontFamily = StudioFontFamily,
        )
    }
}

internal object StudioShellDefaults {
    val TopbarHeight = 56.dp
    val HistoryWidth = 280.dp
    val CanvasMinWidth = 480.dp
    val InspectorWidth = 340.dp
    val MinimumBodyWidth = 1100.dp
}
