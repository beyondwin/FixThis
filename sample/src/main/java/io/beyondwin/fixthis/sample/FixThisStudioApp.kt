package io.beyondwin.fixthis.sample

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.beyondwin.fixthis.sample.screens.DiagnosticsScreen
import io.beyondwin.fixthis.sample.screens.HomeScreen
import io.beyondwin.fixthis.sample.screens.ProjectScreen
import io.beyondwin.fixthis.sample.screens.QueueScreen
import io.beyondwin.fixthis.sample.screens.ReviewScreen

enum class FixThisTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Outlined.Home),
    Queue("Queue", Icons.AutoMirrored.Outlined.Assignment),
    Project("Project", Icons.Outlined.Work),
    Review("Review", Icons.Outlined.RateReview),
    Diagnostics("Diagnostics", Icons.Outlined.Build),
}

@Composable
fun FixThisStudioApp() {
    FixThisTheme {
        var selectedTabName by rememberSaveable { mutableStateOf(FixThisTab.Home.name) }
        val selected = FixThisTab.entries.firstOrNull { it.name == selectedTabName } ?: FixThisTab.Home

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    FixThisTab.entries.forEach { tab ->
                        NavigationBarItem(
                            modifier = Modifier.semantics {
                                contentDescription = "${tab.label} tab"
                            },
                            selected = selected == tab,
                            onClick = { selectedTabName = tab.name },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                )
                            },
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
