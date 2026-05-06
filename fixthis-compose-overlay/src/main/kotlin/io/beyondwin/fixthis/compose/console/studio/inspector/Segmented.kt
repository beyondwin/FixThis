package io.beyondwin.fixthis.compose.console.studio.inspector

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.beyondwin.fixthis.compose.console.studio.common.StudioFontFamily
import io.beyondwin.fixthis.compose.console.studio.model.AnnotationStatus
import io.beyondwin.fixthis.compose.console.studio.model.Severity
import io.beyondwin.fixthis.compose.console.studio.theme.StudioColors
import io.beyondwin.fixthis.compose.console.studio.theme.severityColor

@Composable
internal fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    activeBg: (T) -> Color,
    activeFg: (T) -> Color,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(StudioColors.Bg2)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            val bg by animateColorAsState(
                targetValue = if (active) activeBg(option) else Color.Transparent,
                animationSpec = tween(120),
                label = "segBg",
            )
            val fg by animateColorAsState(
                targetValue = if (active) activeFg(option) else StudioColors.Txt1,
                animationSpec = tween(120),
                label = "segFg",
            )
            val interaction = remember(option) { MutableInteractionSource() }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(5.dp))
                    .background(bg)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(option) },
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option).uppercase(),
                    color = fg,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.06.em,
                    fontFamily = StudioFontFamily,
                )
            }
        }
    }
}

@Composable
internal fun SeveritySegmented(
    value: Severity,
    onChange: (Severity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Segmented(
        options = listOf(Severity.HIGH, Severity.MED, Severity.LOW),
        selected = value,
        label = ::severityDisplayText,
        activeBg = ::severityColor,
        activeFg = { StudioColors.Bg0 },
        onSelect = onChange,
        modifier = modifier,
    )
}

@Composable
internal fun StatusSegmented(
    value: AnnotationStatus,
    onChange: (AnnotationStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    Segmented(
        options = listOf(
            AnnotationStatus.OPEN,
            AnnotationStatus.IN_PROGRESS,
            AnnotationStatus.RESOLVED,
        ),
        selected = value,
        label = ::annotationStatusDisplayText,
        activeBg = { StudioColors.Bg3 },
        activeFg = { StudioColors.Txt0 },
        onSelect = onChange,
        modifier = modifier,
    )
}
