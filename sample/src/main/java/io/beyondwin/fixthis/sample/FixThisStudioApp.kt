package io.beyondwin.fixthis.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
            TemporaryTabContent(selected, padding)
        }
    }
}

@Composable
private fun TemporaryTabContent(tab: FixThisTab, padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Text(
            text = "FixThis Studio",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(text = tab.label)
    }
}
