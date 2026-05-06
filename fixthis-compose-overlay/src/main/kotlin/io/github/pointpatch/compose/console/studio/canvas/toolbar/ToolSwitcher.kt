package io.github.pointpatch.compose.console.studio.canvas.toolbar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pointpatch.compose.console.studio.StudioTestTags
import io.github.pointpatch.compose.console.studio.common.StudioFontFamily
import io.github.pointpatch.compose.console.studio.model.StudioTool
import io.github.pointpatch.compose.console.studio.theme.StudioColors

@Composable
internal fun ToolSwitcher(
    tool: StudioTool,
    onToolChange: (StudioTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(StudioColors.Bg2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolButton(
            label = "Select",
            active = tool == StudioTool.SELECT,
            onClick = { onToolChange(StudioTool.SELECT) },
            testTag = StudioTestTags.ToolSelect,
            icon = { color -> ToolPointerIcon(color = color) },
        )
        ToolButton(
            label = "Annotate",
            active = tool == StudioTool.ANNOTATE,
            onClick = { onToolChange(StudioTool.ANNOTATE) },
            testTag = StudioTestTags.ToolAnnotate,
            icon = { color -> ToolDashedRectIcon(color = color) },
        )
    }
}

@Composable
private fun ToolButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    testTag: String,
    icon: @Composable (Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = when {
            active -> StudioColors.Bg3
            hovered -> StudioColors.Bg2.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "toolBg",
    )
    val fg by animateColorAsState(
        targetValue = when {
            active -> StudioColors.Accent
            hovered -> StudioColors.Txt0
            else -> StudioColors.Txt1
        },
        animationSpec = tween(120),
        label = "toolFg",
    )
    val shape = RoundedCornerShape(6.dp)

    Row(
        modifier = Modifier
            .then(if (active) Modifier.shadow(1.dp, shape) else Modifier)
            .clip(shape)
            .background(bg)
            .testTag(testTag)
            .semantics { selected = active }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon(fg)
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = StudioFontFamily,
        )
    }
}

@Composable
private fun ToolPointerIcon(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.08f)
            lineTo(size.width * 0.84f, size.height * 0.56f)
            lineTo(size.width * 0.53f, size.height * 0.62f)
            lineTo(size.width * 0.46f, size.height * 0.94f)
            close()
        }
        drawPath(path = path, color = color)
    }
}

@Composable
private fun ToolDashedRectIcon(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        drawRoundRect(
            color = color,
            style = Stroke(
                width = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)),
            ),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
    }
}
