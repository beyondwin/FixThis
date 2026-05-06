package io.beyondwin.fixthis.sample.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.components.SectionHeader
import io.beyondwin.fixthis.sample.components.SeverityChip
import io.beyondwin.fixthis.sample.components.StateChip
import io.beyondwin.fixthis.sample.model.ActivityEvent
import io.beyondwin.fixthis.sample.model.FixThisDemoData

@Composable
fun ProjectScreen(padding: PaddingValues) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var closeDialogOpen by rememberSaveable { mutableStateOf(false) }
    val item = FixThisDemoData.feedbackItems.first()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Project detail")
                Text("${item.id} - ${item.title}", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeverityChip(item.severity)
                    StateChip(item.state)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Affected preview", style = MaterialTheme.typography.titleMedium)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            ),
                    )
                    Text(
                        text = "Area selection should remain useful on this visual preview surface.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Reproduction note", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Open payment summary from a saved cart, increase system font scale, then compare the primary action against the summary panel. The target remains tappable, but the visual weight makes it easy to miss during review and handoff.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Agent handoff: adjust purchase CTA contrast and verify source candidates.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            Box {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text("More actions")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Assign reviewer") },
                        onClick = { menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Close issue") },
                        onClick = {
                            menuOpen = false
                            closeDialogOpen = true
                        },
                    )
                }
            }
        }
        item { SectionHeader("Timeline") }
        items(FixThisDemoData.activity) { activity ->
            TimelineCard(activity)
        }
    }

    if (closeDialogOpen) {
        AlertDialog(
            onDismissRequest = { closeDialogOpen = false },
            title = { Text("Close issue") },
            text = { Text("Close ${item.id} and keep this feedback in the project history?") },
            confirmButton = {
                TextButton(onClick = { closeDialogOpen = false }) {
                    Text("Close issue")
                }
            },
            dismissButton = {
                TextButton(onClick = { closeDialogOpen = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun TimelineCard(activity: ActivityEvent) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(activity.title, style = MaterialTheme.typography.titleSmall)
            Text(activity.detail, style = MaterialTheme.typography.bodyMedium)
            Text(
                activity.timeLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
