package io.github.pointpatch.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CheckoutScreen(padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Checkout") }
        item {
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Total due") },
                    supportingContent = { Text("\$32.00") }
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Coupon applied") },
                    supportingContent = { Text("Spring discount") }
                )
            }
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {}
            ) {
                Text("Pay now")
            }
        }
    }
}
