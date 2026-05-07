package io.beyondwin.fixthis.compose.console.studio.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.compose.console.studio.canvas.toolbar.ToolStatusBar
import io.beyondwin.fixthis.compose.console.studio.canvas.toolbar.ToolSwitcher
import io.beyondwin.fixthis.compose.console.studio.canvas.toolbar.ZoomControl
import io.beyondwin.fixthis.compose.console.studio.common.bottomBorder
import io.beyondwin.fixthis.compose.console.studio.model.StudioTool
import io.beyondwin.fixthis.compose.console.studio.theme.StudioColors

@Composable
internal fun CanvasToolbar(
    tool: StudioTool,
    onToolChange: (StudioTool) -> Unit,
    openCount: Int,
    resolvedCount: Int,
    interactionMode: CanvasInteractionMode,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioColors.Bg1)
            .bottomBorder(StudioColors.Line)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ToolSwitcher(tool = tool, onToolChange = onToolChange)
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            ToolStatusBar(
                tool = tool,
                openCount = openCount,
                resolvedCount = resolvedCount,
                interactionMode = interactionMode,
            )
        }
        ZoomControl()
    }
}
