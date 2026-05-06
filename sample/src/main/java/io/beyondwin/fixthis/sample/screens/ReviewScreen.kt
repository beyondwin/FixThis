package io.beyondwin.fixthis.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.components.SectionHeader

@Composable
fun ReviewScreen(padding: PaddingValues) {
    var title by rememberSaveable { mutableStateOf("Increase checkout CTA contrast") }
    var target by rememberSaveable { mutableStateOf("Checkout / Bottom bar") }
    var token by rememberSaveable { mutableStateOf("agent-context-token") }
    var screenshot by rememberSaveable { mutableStateOf(true) }
    var sendToAgent by rememberSaveable { mutableStateOf(true) }
    var severity by rememberSaveable { mutableStateOf("High") }
    var severityOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Review request")
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = title,
            onValueChange = { title = it },
            label = { Text("Request title") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = target,
            onValueChange = { target = it },
            label = { Text("Target screen") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = token,
            onValueChange = { token = it },
            label = { Text("Agent token") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            supportingText = { Text("Token is masked but remains editable for handoff checks.") },
        )
        Box {
            OutlinedButton(onClick = { severityOpen = true }) {
                Text("Severity: $severity")
            }
            DropdownMenu(
                expanded = severityOpen,
                onDismissRequest = { severityOpen = false },
            ) {
                listOf("Critical", "High", "Medium", "Low").forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            severity = option
                            severityOpen = false
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                modifier = Modifier.semantics {
                    contentDescription = "Include screenshot context"
                },
                checked = screenshot,
                onCheckedChange = { screenshot = it },
            )
            Text("Include screenshot context", style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (sendToAgent) "Send to agent queue" else "Keep as draft",
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                modifier = Modifier.semantics {
                    contentDescription = "Send to agent queue"
                },
                checked = sendToAgent,
                onCheckedChange = { sendToAgent = it },
            )
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {},
        ) {
            Text("Submit request")
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { title = "" },
        ) {
            Text("Clear draft")
        }
    }
}
