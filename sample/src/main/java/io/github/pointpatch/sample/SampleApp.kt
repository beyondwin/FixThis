package io.github.pointpatch.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pointpatch.sample.screens.CanvasScreen
import io.github.pointpatch.sample.screens.CheckoutScreen
import io.github.pointpatch.sample.screens.DialogScreen
import io.github.pointpatch.sample.screens.EdgeCasesScreen
import io.github.pointpatch.sample.screens.FeedScreen
import io.github.pointpatch.sample.screens.FormScreen

private enum class SampleTab(val label: String, val markerColor: Color) {
    Checkout("Checkout", Color(0xff2f6fbd)),
    Feed("Feed", Color(0xff6c8f1f)),
    Form("Form", Color(0xff8a4f9e)),
    Dialog("Dialog", Color(0xffb85c38)),
    Canvas("Canvas", Color(0xff008c7a)),
    Edge("Edge", Color(0xff5d6673))
}

@Composable
fun SampleApp() {
    MaterialTheme {
        var selectedTabName by rememberSaveable { mutableStateOf(SampleTab.Checkout.name) }
        val selected = SampleTab.entries.firstOrNull { it.name == selectedTabName } ?: SampleTab.Checkout
        Scaffold(
            bottomBar = {
                NavigationBar {
                    SampleTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab,
                            onClick = { selectedTabName = tab.name },
                            label = {
                                Text(
                                    text = tab.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            icon = {
                                TabMarker(tab)
                            }
                        )
                    }
                }
            }
        ) { padding ->
            Column {
                when (selected) {
                    SampleTab.Checkout -> CheckoutScreen(padding)
                    SampleTab.Feed -> FeedScreen(padding)
                    SampleTab.Form -> FormScreen(padding)
                    SampleTab.Dialog -> DialogScreen(padding)
                    SampleTab.Canvas -> CanvasScreen(padding)
                    SampleTab.Edge -> EdgeCasesScreen(padding)
                }
            }
        }
    }
}

@Composable
private fun TabMarker(tab: SampleTab) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(tab.markerColor)
    )
}
