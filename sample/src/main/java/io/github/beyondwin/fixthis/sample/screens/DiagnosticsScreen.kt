package io.github.beyondwin.fixthis.sample.screens

import android.graphics.Color
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.beyondwin.fixthis.sample.components.InfoRow
import io.github.beyondwin.fixthis.sample.components.PreviewPanel
import io.github.beyondwin.fixthis.sample.components.SparklineSurface
import io.github.beyondwin.fixthis.sample.components.StateChip
import io.github.beyondwin.fixthis.sample.components.StudioHeader
import io.github.beyondwin.fixthis.sample.model.FixThisDemoData

private const val ANDROID_VIEW_BACKGROUND_RED = 49
private const val ANDROID_VIEW_BACKGROUND_GREEN = 79
private const val ANDROID_VIEW_BACKGROUND_BLUE = 124
private const val ANDROID_VIEW_HORIZONTAL_PADDING = 24
private const val ANDROID_VIEW_VERTICAL_PADDING = 18
private const val ANDROID_VIEW_HEIGHT_DP = 72

@Composable
fun DiagnosticsScreen(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .testTag("screen:Diagnostics:root")
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
        androidViewInteropFixture()
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

@Composable
private fun androidViewInteropFixture() {
    Text("AndroidView interop preview", style = MaterialTheme.typography.titleSmall)
    AndroidView(
        modifier = Modifier
            .testTag("comp:NativeChartHost:chart")
            .fillMaxWidth()
            .height(ANDROID_VIEW_HEIGHT_DP.dp),
        factory = { context ->
            TextView(context).apply {
                text = "Native AndroidView target"
                contentDescription = "Native AndroidView target"
                setTextColor(Color.WHITE)
                setBackgroundColor(
                    Color.rgb(
                        ANDROID_VIEW_BACKGROUND_RED,
                        ANDROID_VIEW_BACKGROUND_GREEN,
                        ANDROID_VIEW_BACKGROUND_BLUE,
                    ),
                )
                setPadding(
                    ANDROID_VIEW_HORIZONTAL_PADDING,
                    ANDROID_VIEW_VERTICAL_PADDING,
                    ANDROID_VIEW_HORIZONTAL_PADDING,
                    ANDROID_VIEW_VERTICAL_PADDING,
                )
            }
        },
    )
}
