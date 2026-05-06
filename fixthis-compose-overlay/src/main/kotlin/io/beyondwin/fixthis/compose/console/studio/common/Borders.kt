package io.beyondwin.fixthis.compose.console.studio.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun Modifier.topBorder(
    color: Color,
    thickness: Dp = 1.dp,
): Modifier = drawBehind {
    val stroke = thickness.toPx()
    drawLine(
        color = color,
        start = Offset(0f, stroke / 2f),
        end = Offset(size.width, stroke / 2f),
        strokeWidth = stroke,
    )
}

internal fun Modifier.bottomBorder(
    color: Color,
    thickness: Dp = 1.dp,
): Modifier = drawBehind {
    val stroke = thickness.toPx()
    drawLine(
        color = color,
        start = Offset(0f, size.height - stroke / 2f),
        end = Offset(size.width, size.height - stroke / 2f),
        strokeWidth = stroke,
    )
}

internal fun Modifier.leftBorder(
    color: Color,
    thickness: Dp = 1.dp,
): Modifier = drawBehind {
    val stroke = thickness.toPx()
    drawLine(
        color = color,
        start = Offset(stroke / 2f, 0f),
        end = Offset(stroke / 2f, size.height),
        strokeWidth = stroke,
    )
}

internal fun Modifier.rightBorder(
    color: Color,
    thickness: Dp = 1.dp,
): Modifier = drawBehind {
    val stroke = thickness.toPx()
    drawLine(
        color = color,
        start = Offset(size.width - stroke / 2f, 0f),
        end = Offset(size.width - stroke / 2f, size.height),
        strokeWidth = stroke,
    )
}
