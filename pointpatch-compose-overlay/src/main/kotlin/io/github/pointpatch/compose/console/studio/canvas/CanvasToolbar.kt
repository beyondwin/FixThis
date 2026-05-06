package io.github.pointpatch.compose.console.studio.canvas

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pointpatch.compose.console.studio.StudioTestTags
import io.github.pointpatch.compose.console.studio.common.StudioFontFamily
import io.github.pointpatch.compose.console.studio.common.bottomBorder
import io.github.pointpatch.compose.console.studio.model.StudioTool
import io.github.pointpatch.compose.console.studio.theme.StudioColors

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
        ToolGroup(tool = tool, onToolChange = onToolChange)
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when (tool) {
                StudioTool.SELECT -> ToolStatusMeta(open = openCount, resolved = resolvedCount)
                StudioTool.ANNOTATE -> ToolStatusHint(interactionMode = interactionMode)
            }
        }
        ToolZoom()
    }
}

@Composable
internal fun ToolGroup(
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
internal fun ToolStatusMeta(
    open: Int,
    resolved: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDotLabel(
            text = "$open open",
            dotColor = StudioColors.StatusOpen,
            glowColor = StudioColors.StatusOpen.copy(alpha = 0.18f),
        )
        StatusDotLabel(
            text = "$resolved resolved",
            dotColor = StudioColors.StatusResolved,
            glowColor = StudioColors.StatusResolved.copy(alpha = 0.18f),
        )
    }
}

@Composable
private fun StatusDotLabel(
    text: String,
    dotColor: Color,
    glowColor: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = glowColor, radius = 4.dp.toPx())
            drawCircle(color = dotColor, radius = 2.5f.dp.toPx())
        }
        Text(
            text = text,
            color = StudioColors.Txt1,
            fontSize = 11.sp,
            fontFamily = StudioFontFamily,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

@Composable
internal fun ToolStatusHint(
    interactionMode: CanvasInteractionMode,
    modifier: Modifier = Modifier,
) {
    val isUnavailable = interactionMode == CanvasInteractionMode.AnnotationUnavailable
    val infinite = rememberInfiniteTransition(label = "pulseA")
    val opacity by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (isUnavailable) 1f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hintDotOpacity",
    )
    val scale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (isUnavailable) 1f else 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hintDotScale",
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (isUnavailable) StudioColors.Bg2 else StudioColors.AnnotateHintBg)
            .border(
                1.dp,
                if (isUnavailable) StudioColors.Line else StudioColors.AnnotateHintBorder,
                RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer {
                    alpha = opacity
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(if (isUnavailable) StudioColors.Txt2 else StudioColors.Accent),
        )
        Text(
            text = annotateHintText(interactionMode),
            color = if (isUnavailable) StudioColors.Txt2 else StudioColors.Accent,
            fontSize = 11.sp,
            fontFamily = StudioFontFamily,
        )
    }
}

internal fun annotateHintText(interactionMode: CanvasInteractionMode): String =
    when (interactionMode) {
        CanvasInteractionMode.WidgetSnapAndRegion -> "Click a widget — or drag to draw a region"
        CanvasInteractionMode.RegionOnly -> "Drag to draw a region"
        CanvasInteractionMode.AnnotationUnavailable -> "Annotation unavailable"
    }

@Composable
internal fun ToolZoom(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(StudioColors.Bg2)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZoomButton("−")
        Text(
            text = "100%",
            color = StudioColors.Txt1,
            fontSize = 11.sp,
            fontFamily = StudioFontFamily,
            style = TextStyle(fontFeatureSettings = "tnum"),
            modifier = Modifier
                .widthIn(min = 34.dp)
                .padding(horizontal = 6.dp),
        )
        ZoomButton("+")
    }
}

@Composable
private fun ZoomButton(symbol: String) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) StudioColors.Bg3 else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            color = if (hovered) StudioColors.Txt0 else StudioColors.Txt1,
            fontSize = 14.sp,
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
