package io.beyondwin.fixthis.compose.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun FixThisToolbar(
    modifier: Modifier = Modifier,
    onSelectUi: () -> Unit,
    onRecent: () -> Unit = {},
    onConnectAiAgent: () -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    FixThisToolbar(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
        onSelectUi = onSelectUi,
        onRecent = onRecent,
        onConnectAiAgent = onConnectAiAgent,
    )
}

@Composable
fun FixThisToolbar(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onSelectUi: () -> Unit,
    onRecent: () -> Unit = {},
    onConnectAiAgent: () -> Unit = {},
) {
    Box(modifier = modifier) {
        FilledTonalIconButton(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier.size(56.dp),
        ) {
            Text(
                text = "PP",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            DropdownMenuItem(
                text = { MenuLabel("Select UI") },
                onClick = {
                    onExpandedChange(false)
                    onSelectUi()
                },
            )
            DropdownMenuItem(
                text = { MenuLabel("Recent") },
                enabled = false,
                onClick = onRecent,
            )
            DropdownMenuItem(
                text = { MenuLabel("Connect AI Agent") },
                enabled = false,
                onClick = onConnectAiAgent,
            )
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Preview
@Composable
private fun FixThisToolbarPreview() {
    MaterialTheme {
        FixThisToolbar(onSelectUi = {})
    }
}
