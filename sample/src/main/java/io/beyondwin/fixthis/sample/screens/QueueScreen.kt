package io.beyondwin.fixthis.sample.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.components.FeedbackCard
import io.beyondwin.fixthis.sample.components.SectionHeader
import io.beyondwin.fixthis.sample.model.FixThisDemoData

@Composable
fun QueueScreen(padding: PaddingValues) {
    var searchQuery by rememberSaveable { mutableStateOf("checkout contrast") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeader("Feedback queue", "28 open") }
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search feedback") },
                singleLine = true,
            )
        }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text("High priority") },
                )
                FilterChip(
                    selected = false,
                    onClick = {},
                    label = { Text("Assigned to me") },
                )
                FilterChip(
                    selected = false,
                    onClick = {},
                    label = { Text("Needs screenshot") },
                )
            }
        }
        itemsIndexed(FixThisDemoData.feedbackItems) { index, item ->
            FeedbackCard(
                item = item,
                showDisabledAction = index == FixThisDemoData.feedbackItems.lastIndex,
            )
        }
    }
}
