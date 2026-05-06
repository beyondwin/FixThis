package io.beyondwin.fixthis.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.components.InfoRow
import io.beyondwin.fixthis.sample.components.PreviewPanel
import io.beyondwin.fixthis.sample.components.SparklineSurface
import io.beyondwin.fixthis.sample.components.StateChip
import io.beyondwin.fixthis.sample.components.StudioHeader
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
        StudioHeader(
            title = "Diagnostics",
            subtitle = "Inspect selection quality and semantic coverage.",
            status = "Live",
        )
        Text("Visual-only sparkline", style = MaterialTheme.typography.titleSmall)
        SparklineSurface()
        Text("Semantic signal timeline", style = MaterialTheme.typography.titleSmall)
        SparklineSurface(
            modifier = Modifier.semantics {
                contentDescription = "Semantic signal timeline"
            },
        )
        FixThisDemoData.diagnostics.forEach { signal ->
            InfoRow(
                title = signal.label,
                detail = signal.value,
                meta = signal.trendLabel,
            ) {
                StateChip(signal.state)
            }
        }
        PreviewPanel(
            title = "Weak semantic preview",
            subtitle = "Area selection should remain useful on this visual block.",
            height = 96.dp,
        )
        InfoRow(
            title = "Very long diagnostic row label that should wrap without breaking Smart Select behavior",
            detail = "Nested row target remains selectable while the disabled action communicates blocked state.",
            meta = "Blocked control",
            clickable = true,
        ) {
            Button(enabled = false, onClick = {}) {
                Text("Disabled action")
            }
        }
    }
}
