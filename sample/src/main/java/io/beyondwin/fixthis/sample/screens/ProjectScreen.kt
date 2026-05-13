@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.beyondwin.fixthis.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.components.InfoRow
import io.beyondwin.fixthis.sample.components.PreviewPanel
import io.beyondwin.fixthis.sample.components.SectionHeader
import io.beyondwin.fixthis.sample.components.SeverityChip
import io.beyondwin.fixthis.sample.components.StateChip
import io.beyondwin.fixthis.sample.components.StudioHeader
import io.beyondwin.fixthis.sample.model.FixThisDemoData

@Composable
fun ProjectScreen(padding: PaddingValues) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var closeDialogOpen by rememberSaveable { mutableStateOf(false) }
    val item = FixThisDemoData.feedbackItems.first()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .testTag("screen:Project:list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StudioHeader(
                    title = item.id,
                    subtitle = item.title,
                    status = item.state.label,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SeverityChip(item.severity)
                    StateChip(item.state)
                }
            }
        }
        item {
            PreviewPanel(
                title = "Affected preview",
                subtitle = "${item.screenName} - ${item.captureLabel}",
            )
        }
        item { SectionHeader("Feedback context") }
        item {
            InfoRow(
                title = "Source confidence",
                detail = item.sourceConfidence,
                meta = "${item.screenName} - ${item.captureLabel}",
            )
        }
        item {
            InfoRow(
                title = "Owner",
                detail = item.assignee,
                meta = "Assigned ${item.ageLabel} ago",
            )
        }
        item {
            InfoRow(
                title = "Reproduction note",
                detail = "Open payment summary from a saved cart, increase system font scale, then compare the primary action against the summary panel. The target remains tappable, but the visual weight makes it easy to miss during review and handoff.",
                meta = item.summary,
            )
        }
        item {
            InfoRow(
                title = "Agent handoff",
                detail = "Adjust purchase CTA contrast and verify source candidates.",
                meta = "Ready for review composer",
                trailing = { SeverityChip(item.severity) },
            )
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { closeDialogOpen = true }) {
                    Text("Close issue")
                }
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
        }
        item { SectionHeader("Timeline") }
        items(FixThisDemoData.activity) { activity ->
            InfoRow(
                title = activity.title,
                detail = activity.detail,
                meta = "${activity.category} - ${activity.timeLabel}",
            )
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
