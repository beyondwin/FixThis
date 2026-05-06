package io.beyondwin.fixthis.compose.console.studio.canvas

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.beyondwin.fixthis.compose.console.studio.common.StudioFontFamily
import io.beyondwin.fixthis.compose.console.studio.model.Annotation
import io.beyondwin.fixthis.compose.console.studio.theme.StudioColors
import io.beyondwin.fixthis.compose.console.studio.theme.severityColor

@Composable
internal fun PinRect(
    annotation: Annotation,
    index: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val previewSize = LocalPreviewSizePx.current
    if (previewSize.width <= 0 || previewSize.height <= 0) return

    val density = LocalDensity.current
    val boundsPx = rectPercentToPixelBounds(annotation.rectPercent, previewSize)
    val leftDp = with(density) { boundsPx.left.toDp() }
    val topDp = with(density) { boundsPx.top.toDp() }
    val widthDp = with(density) { boundsPx.width.toDp() }
    val heightDp = with(density) { boundsPx.height.toDp() }
    val color = severityColor(annotation.severity)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fillAlpha = when {
        isSelected -> 0.20f
        hovered && enabled -> 0.20f
        else -> 0.12f
    }
    val fill by animateColorAsState(
        targetValue = color.copy(alpha = fillAlpha),
        animationSpec = tween(120),
        label = "pinFill",
    )
    val shape = RoundedCornerShape(4.dp)

    Box(
        modifier = modifier
            .offset(x = leftDp, y = topDp)
            .size(width = widthDp, height = heightDp)
            .then(if (isSelected) Modifier.shadow(8.dp, shape, clip = false) else Modifier)
            .then(
                if (isSelected) {
                    Modifier.drawBehind { drawSelectedGlow(color) }
                } else {
                    Modifier
                },
            )
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(fill)
                .border(if (isSelected) 2.dp else 1.5.dp, color, shape),
        )
        PinTag(
            label = (index + 1).toString(),
            background = color,
        )
    }
}

@Composable
private fun PinTag(
    label: String,
    background: Color,
) {
    Box(
        modifier = Modifier
            .offset(x = (-10).dp, y = (-10).dp)
            .size(22.dp)
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = StudioColors.Bg0,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = StudioFontFamily,
        )
    }
}

private fun DrawScope.drawSelectedGlow(color: Color) {
    val ringPx = 2.dp.toPx()
    drawRoundRect(
        color = color.copy(alpha = 0.25f),
        topLeft = Offset(x = -ringPx, y = -ringPx),
        size = Size(width = size.width + ringPx * 2f, height = size.height + ringPx * 2f),
        cornerRadius = CornerRadius(4.dp.toPx() + ringPx),
        style = Stroke(width = ringPx),
    )
}
