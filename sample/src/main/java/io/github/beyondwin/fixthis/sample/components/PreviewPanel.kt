package io.github.beyondwin.fixthis.sample.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun PreviewPanel(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    height: Dp = 132.dp,
    contentDescription: String? = null,
) {
    val semanticsModifier = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(semanticsModifier)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        SparklineSurface(height = height)
    }
}

@Composable
fun SparklineSurface(
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)),
    ) {
        val points = listOf(0.72f, 0.45f, 0.58f, 0.32f, 0.5f, 0.25f, 0.38f)
        val step = size.width / points.lastIndex.coerceAtLeast(1)
        val path = Path()
        points.forEachIndexed { index, value ->
            val point = Offset(index * step, size.height * value)
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawLine(
            color = track,
            start = Offset(0f, size.height * 0.72f),
            end = Offset(size.width, size.height * 0.72f),
            strokeWidth = 1.dp.toPx(),
        )
        drawPath(
            path = path,
            color = primary,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
