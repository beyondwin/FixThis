package io.beyondwin.fixthis.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.components.FeedbackCard
import io.beyondwin.fixthis.sample.components.MetricCard
import io.beyondwin.fixthis.sample.components.SectionHeader
import io.beyondwin.fixthis.sample.model.ActivityEvent
import io.beyondwin.fixthis.sample.model.FixThisDemoData

@Composable
fun HomeScreen(padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "FixThis Studio",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Track app feedback, prepare agent handoffs, and keep review work moving from one compact workspace.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { SectionHeader("Project health", "Refresh") }
        items(FixThisDemoData.metrics) { metric ->
            MetricCard(metric)
        }
        item { SectionHeader("Priority feedback", "Open queue") }
        items(FixThisDemoData.feedbackItems.take(2)) { item ->
            FeedbackCard(item)
        }
        item { SectionHeader("Recent activity") }
        items(FixThisDemoData.activity) { activity ->
            ActivityCard(activity)
        }
    }
}

@Composable
private fun ActivityCard(activity: ActivityEvent) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
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
