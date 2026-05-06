package io.beyondwin.fixthis.sample

import androidx.compose.foundation.layout.PaddingValues
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
import io.beyondwin.fixthis.sample.screens.DiagnosticsScreen
import io.beyondwin.fixthis.sample.screens.HomeScreen
import io.beyondwin.fixthis.sample.screens.ProjectScreen
import io.beyondwin.fixthis.sample.screens.QueueScreen
import io.beyondwin.fixthis.sample.screens.ReviewScreen

enum class FixThisTab(val label: String) {
    Home("Home"),
    Queue("Queue"),
    Project("Project"),
    Review("Review"),
    Diagnostics("Diagnostics"),
}

@Composable
fun FixThisStudioApp() {
    FixThisTheme {
        var selectedTabName by rememberSaveable { mutableStateOf(FixThisTab.Home.name) }
        val selected = FixThisTab.entries.firstOrNull { it.name == selectedTabName } ?: FixThisTab.Home

        Scaffold(
            bottomBar = {
                NavigationBar {
                    FixThisTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab,
                            onClick = { selectedTabName = tab.name },
                            icon = { Text(tab.label.take(1), style = MaterialTheme.typography.labelMedium) },
                            label = { Text(tab.label, maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding ->
            FixThisTabContent(selected, padding)
        }
    }
}

@Composable
private fun FixThisTabContent(tab: FixThisTab, padding: PaddingValues) {
    when (tab) {
        FixThisTab.Home -> HomeScreen(padding)
        FixThisTab.Queue -> QueueScreen(padding)
        FixThisTab.Project -> ProjectScreen(padding)
        FixThisTab.Review -> ReviewScreen(padding)
        FixThisTab.Diagnostics -> DiagnosticsScreen(padding)
    }
}
