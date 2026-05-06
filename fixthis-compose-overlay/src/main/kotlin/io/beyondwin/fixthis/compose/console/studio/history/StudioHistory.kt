package io.beyondwin.fixthis.compose.console.studio.history

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.beyondwin.fixthis.compose.console.studio.common.PanelHead
import io.beyondwin.fixthis.compose.console.studio.common.StudioFontFamily
import io.beyondwin.fixthis.compose.console.studio.common.StudioType
import io.beyondwin.fixthis.compose.console.studio.common.rightBorder
import io.beyondwin.fixthis.compose.console.studio.model.Annotation
import io.beyondwin.fixthis.compose.console.studio.model.AnnotationStatus
import io.beyondwin.fixthis.compose.console.studio.model.Snapshot
import io.beyondwin.fixthis.compose.console.studio.theme.StudioColors
import io.beyondwin.fixthis.compose.console.studio.theme.severityColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun StudioHistory(
    snapshots: List<Snapshot>,
    activeSnapId: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(StudioColors.Bg1)
            .rightBorder(StudioColors.Line),
    ) {
        PanelHead(title = "History", count = snapshots.size)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items = snapshots, key = { it.id }) { snapshot ->
                HistoryItem(
                    snapshot = snapshot,
                    isActive = snapshot.id == activeSnapId,
                    onClick = { onOpen(snapshot.id) },
                    onDelete = { onDelete(snapshot.id) },
                )
            }
        }
    }
}

@Composable
internal fun HistoryItem(
    snapshot: Snapshot,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (isActive || hovered) StudioColors.Bg2 else Color.Transparent,
        animationSpec = tween(120),
        label = "hiBg",
    )
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .then(if (isActive) Modifier.border(1.dp, StudioColors.Line, shape) else Modifier)
            .then(
                if (isActive) {
                    Modifier.drawActiveAccent()
                } else {
                    Modifier
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = snapshot.title,
                    style = StudioType.HiTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                DeleteIconButton(
                    visible = hovered,
                    onClick = onDelete,
                )
            }
            Text(
                text = formatHistoryMeta(snapshot),
                color = StudioColors.Txt2,
                fontSize = 11.sp,
                fontFamily = StudioFontFamily,
            )
            HistoryStats(annotations = snapshot.annotations)
            HistoryStrip(annotations = snapshot.annotations)
        }
    }
}

@Composable
internal fun HistoryStats(
    annotations: List<Annotation>,
    modifier: Modifier = Modifier,
) {
    val counts = historyStatCounts(annotations)
    Row(
        modifier = modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Pip("${counts.open} open")
        Pip("${counts.resolved} resolved")
    }
}

@Composable
internal fun HistoryStrip(
    annotations: List<Annotation>,
    modifier: Modifier = Modifier,
) {
    if (annotations.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        annotations.forEach { annotation ->
            val baseColor = severityColor(annotation.severity)
            val tint = if (annotation.status == AnnotationStatus.RESOLVED) {
                baseColor.copy(alpha = 0.35f)
            } else {
                baseColor
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint),
            )
        }
    }
}

internal data class HistoryStatCounts(
    val open: Int,
    val resolved: Int,
)

internal fun historyStatCounts(annotations: List<Annotation>): HistoryStatCounts =
    HistoryStatCounts(
        open = annotations.count { it.status == AnnotationStatus.OPEN },
        resolved = annotations.count { it.status == AnnotationStatus.RESOLVED },
    )

internal fun formatHistoryMeta(snapshot: Snapshot): String {
    val formatted = SimpleDateFormat("MMM d · HH:mm", Locale.ENGLISH)
        .format(Date(snapshot.createdAtEpochMillis))
    return "${snapshot.author} · $formatted"
}

private fun Modifier.drawActiveAccent(): Modifier =
    drawBehind {
        drawRect(
            color = StudioColors.Accent,
            topLeft = Offset.Zero,
            size = Size(2.dp.toPx(), size.height),
        )
    }

@Composable
private fun Pip(text: String) {
    Text(
        text = text,
        style = StudioType.PanelCount.copy(color = StudioColors.Txt1),
    )
}

@Composable
private fun DeleteIconButton(
    visible: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) StudioColors.Bg3 else Color.Transparent,
        animationSpec = tween(120),
        label = "delBg",
    )
    val fg by animateColorAsState(
        targetValue = if (hovered) StudioColors.Danger else StudioColors.Txt2,
        animationSpec = tween(120),
        label = "delFg",
    )

    Box(
        modifier = Modifier
            .size(20.dp)
            .alpha(if (visible) 1f else 0f)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .hoverable(interaction)
            .clickable(enabled = visible, interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "×", color = fg, fontSize = 16.sp, fontFamily = StudioFontFamily)
    }
}
