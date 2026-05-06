package io.github.pointpatch.compose.console.studio.inspector

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.pointpatch.compose.console.studio.common.StudioFontFamily
import io.github.pointpatch.compose.console.studio.common.StudioType
import io.github.pointpatch.compose.console.studio.model.Annotation
import io.github.pointpatch.compose.console.studio.model.AnnotationStatus
import io.github.pointpatch.compose.console.studio.theme.StudioColors
import io.github.pointpatch.compose.console.studio.theme.severityColor
import io.github.pointpatch.compose.console.studio.theme.statusDotColor
import io.github.pointpatch.compose.console.studio.theme.statusPillBg

@Composable
internal fun AnnotationRow(
    annotation: Annotation,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) StudioColors.Bg2 else Color.Transparent,
        animationSpec = tween(120),
        label = "annRowBg",
    )
    val preview = annotationRowCommentPreview(annotation.comment)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(severityColor(annotation.severity)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (index + 1).toString(),
                color = StudioColors.Bg0,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = StudioFontFamily,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = annotation.label,
                style = StudioType.AnnRowTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = preview.text,
                style = StudioType.AnnRowComment.copy(
                    color = if (preview.isPlaceholder) StudioColors.MutedPlaceholder else StudioColors.Txt1,
                ),
                fontStyle = if (preview.isPlaceholder) FontStyle.Italic else FontStyle.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusPill(status = annotation.status)
    }
}

@Composable
internal fun StatusPill(
    status: AnnotationStatus,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(statusPillBg(status))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = annotationStatusPillLabel(status),
            color = statusDotColor(status),
            fontSize = 10.sp,
            letterSpacing = 0.08.em,
            fontWeight = FontWeight.SemiBold,
            fontFamily = StudioFontFamily,
        )
    }
}

internal data class AnnotationRowCommentPreview(
    val text: String,
    val isPlaceholder: Boolean,
)

internal fun annotationRowCommentPreview(comment: String): AnnotationRowCommentPreview =
    if (comment.isBlank()) {
        AnnotationRowCommentPreview(text = "No comment", isPlaceholder = true)
    } else {
        AnnotationRowCommentPreview(text = comment, isPlaceholder = false)
    }

internal fun annotationStatusPillLabel(status: AnnotationStatus): String =
    when (status) {
        AnnotationStatus.OPEN -> "OPEN"
        AnnotationStatus.IN_PROGRESS -> "IN-PROGRESS"
        AnnotationStatus.RESOLVED -> "RESOLVED"
    }
