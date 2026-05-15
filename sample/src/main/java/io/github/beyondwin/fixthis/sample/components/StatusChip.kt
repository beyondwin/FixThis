package io.github.beyondwin.fixthis.sample.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.beyondwin.fixthis.sample.FixThisColors
import io.github.beyondwin.fixthis.sample.model.FeedbackSeverity
import io.github.beyondwin.fixthis.sample.model.FeedbackState

@Composable
fun StatusChip(
    label: String,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text = label, color = contentColor, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun SeverityChip(severity: FeedbackSeverity, modifier: Modifier = Modifier) {
    val severityColors = when (severity) {
        FeedbackSeverity.Critical -> FixThisColors.CriticalSoft to FixThisColors.Critical
        FeedbackSeverity.High -> FixThisColors.WarningSoft to FixThisColors.Warning
        FeedbackSeverity.Medium -> FixThisColors.AccentSoft to FixThisColors.Accent
        FeedbackSeverity.Low -> FixThisColors.NeutralSoft to FixThisColors.Neutral
    }
    StatusChip(severity.label, severityColors.first, severityColors.second, modifier)
}

@Composable
fun StateChip(state: FeedbackState, modifier: Modifier = Modifier) {
    val stateColors = when (state) {
        FeedbackState.New -> FixThisColors.AccentSoft to FixThisColors.Accent
        FeedbackState.Triaged -> FixThisColors.WarningSoft to FixThisColors.Warning
        FeedbackState.InReview -> FixThisColors.AccentSoft to FixThisColors.Accent
        FeedbackState.Blocked -> FixThisColors.NeutralSoft to FixThisColors.Neutral
        FeedbackState.Resolved -> FixThisColors.SuccessSoft to FixThisColors.Success
    }
    StatusChip(state.label, stateColors.first, stateColors.second, modifier)
}
