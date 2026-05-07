package io.beyondwin.fixthis.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.components.FeedbackCard
import io.beyondwin.fixthis.sample.components.InfoRow
import io.beyondwin.fixthis.sample.components.MetricCard
import io.beyondwin.fixthis.sample.components.SectionHeader
import io.beyondwin.fixthis.sample.components.StudioHeader
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
            StudioHeader(
                title = "FixThis Studio",
                subtitle = "Review work, at a glance",
                status = "Live",
            )
        }
        item {
            HomePrimaryAction()
        }
        itemsIndexed(FixThisDemoData.metrics) { index, metric ->
            MetricCard(
                metric,
                modifier = if (index < SummaryMetricCardCount) {
                    Modifier.testTag("comp:MetricCard:summary")
                } else {
                    Modifier
                },
            )
        }
        item { SectionHeader("Priority feedback", "Open queue") }
        items(FixThisDemoData.feedbackItems.take(2)) { item ->
            FeedbackCard(item)
        }
        item { SectionHeader("Recent activity") }
        items(FixThisDemoData.activity) { activity ->
            InfoRow(
                activity.title,
                activity.detail,
                "${activity.category} - ${activity.timeLabel}",
            )
        }
    }
}

@Composable
private fun HomePrimaryAction() {
    SectionHeader(
        title = "Project health",
        action = "Refresh",
        actionModifier = Modifier.testTag("comp:HomePrimaryAction:primary"),
    )
}

private const val SummaryMetricCardCount = 3
