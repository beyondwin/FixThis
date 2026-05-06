package io.github.pointpatch.compose.console.studio.canvas.toolbar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pointpatch.compose.console.studio.canvas.CanvasInteractionMode
import io.github.pointpatch.compose.console.studio.common.StudioFontFamily
import io.github.pointpatch.compose.console.studio.model.StudioTool
import io.github.pointpatch.compose.console.studio.theme.StudioColors

@Composable
internal fun ToolStatusBar(
    tool: StudioTool,
    openCount: Int,
    resolvedCount: Int,
    interactionMode: CanvasInteractionMode,
    modifier: Modifier = Modifier,
) {
    when (tool) {
        StudioTool.SELECT -> ToolStatusMeta(open = openCount, resolved = resolvedCount, modifier = modifier)
        StudioTool.ANNOTATE -> ToolStatusHint(interactionMode = interactionMode, modifier = modifier)
    }
}

internal fun annotateHintText(interactionMode: CanvasInteractionMode): String =
    when (interactionMode) {
        CanvasInteractionMode.WidgetSnapAndRegion -> "Click a widget — or drag to draw a region"
        CanvasInteractionMode.RegionOnly -> "Drag to draw a region"
        CanvasInteractionMode.AnnotationUnavailable -> "Annotation unavailable"
    }

@Composable
private fun ToolStatusMeta(
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
private fun ToolStatusHint(
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
