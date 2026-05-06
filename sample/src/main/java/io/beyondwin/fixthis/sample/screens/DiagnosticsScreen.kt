package io.beyondwin.fixthis.sample.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.components.SectionHeader
import io.beyondwin.fixthis.sample.components.StateChip
import io.beyondwin.fixthis.sample.model.FixThisDemoData

@Composable
fun DiagnosticsScreen(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Diagnostics", "Live")
        Text("Visual-only sparkline", style = MaterialTheme.typography.titleSmall)
        SparklineCanvas()
        Text("Semantic signal timeline", style = MaterialTheme.typography.titleSmall)
        SparklineCanvas(
            modifier = Modifier.semantics {
                contentDescription = "Semantic signal timeline"
            },
        )
        FixThisDemoData.diagnostics.forEach { signal ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(signal.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            signal.value,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    StateChip(signal.state)
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                ),
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = {})
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "Very long diagnostic row label that should wrap without breaking Smart Select behavior",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    enabled = false,
                    onClick = {},
                ) {
                    Text("Disabled action")
                }
            }
        }
    }
}

@Composable
private fun SparklineCanvas(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(surfaceVariant, RoundedCornerShape(8.dp)),
    ) {
        val points = listOf(0.72f, 0.45f, 0.58f, 0.32f, 0.5f, 0.25f, 0.38f)
        val step = size.width / points.lastIndex.coerceAtLeast(1)
        val path = Path()
        points.forEachIndexed { index, value ->
            val point = Offset(index * step, size.height * value)
            if (index == 0) {
                path.moveTo(point.x, point.y)
            } else {
                path.lineTo(point.x, point.y)
            }
        }
        drawPath(
            path = path,
            color = primary,
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
