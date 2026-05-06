package io.github.pointpatch.compose.console.studio.inspector

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pointpatch.compose.console.studio.StudioTestTags
import io.github.pointpatch.compose.console.studio.common.DangerButton
import io.github.pointpatch.compose.console.studio.common.GhostButton
import io.github.pointpatch.compose.console.studio.common.PanelHead
import io.github.pointpatch.compose.console.studio.common.StudioFontFamily
import io.github.pointpatch.compose.console.studio.common.StudioType
import io.github.pointpatch.compose.console.studio.common.topBorder
import io.github.pointpatch.compose.console.studio.model.Annotation
import io.github.pointpatch.compose.console.studio.model.AnnotationStatus
import io.github.pointpatch.compose.console.studio.model.Severity
import io.github.pointpatch.compose.console.studio.theme.StudioColors

@Composable
internal fun AnnotationDetail(
    annotation: Annotation,
    annotationCount: Int,
    onBack: () -> Unit,
    onUpdate: ((Annotation) -> Annotation) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(StudioTestTags.AnnotationDetail),
    ) {
        PanelHead(title = "Annotation", count = annotationCount)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackButton(onClick = onBack)

            Field(label = "Label") {
                StudioTextField(
                    value = annotation.label,
                    onValueChange = { value -> onUpdate { it.copy(label = value) } },
                    singleLine = true,
                )
            }
            Field(label = "Severity") {
                SeveritySegmented(
                    value = annotation.severity,
                    onChange = { value -> onUpdate { it.copy(severity = value) } },
                )
            }
            Field(label = "Comment") {
                StudioTextField(
                    value = annotation.comment,
                    onValueChange = { value -> onUpdate { it.copy(comment = value) } },
                    multiline = true,
                    minLines = 4,
                )
            }
            Field(label = "Status") {
                StatusSegmented(
                    value = annotation.status,
                    onChange = { value -> onUpdate { it.copy(status = value) } },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .topBorder(StudioColors.LineSoft)
                .padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DangerButton(label = "Delete", onClick = onDelete)
            GhostButton(label = "Done", onClick = onBack)
        }
    }
}

@Composable
internal fun Field(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label.uppercase(),
            style = StudioType.FieldLabel,
        )
        content()
    }
}

@Composable
internal fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color by animateColorAsState(
        targetValue = if (hovered) StudioColors.Txt0 else StudioColors.Txt2,
        animationSpec = tween(120),
        label = "backFg",
    )
    Text(
        text = "← All annotations",
        color = color,
        fontSize = 11.sp,
        fontFamily = StudioFontFamily,
        modifier = modifier
            .padding(vertical = 4.dp)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    )
}

@Composable
internal fun StudioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    multiline: Boolean = false,
    minLines: Int = 1,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        targetValue = if (focused) StudioColors.Accent else StudioColors.Line,
        animationSpec = tween(120),
        label = "tfBorder",
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = if (multiline) minLines else 1,
        textStyle = StudioType.Base.copy(
            fontSize = 13.sp,
            lineHeight = if (multiline) 19.5.sp else 16.sp,
        ),
        cursorBrush = SolidColor(StudioColors.Accent),
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(StudioColors.Bg2)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .then(if (multiline) Modifier.heightIn(min = 80.dp) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

internal fun severityDisplayText(severity: Severity): String =
    when (severity) {
        Severity.HIGH -> "High"
        Severity.MED -> "Med"
        Severity.LOW -> "Low"
    }

internal fun annotationStatusDisplayText(status: AnnotationStatus): String =
    when (status) {
        AnnotationStatus.OPEN -> "Open"
        AnnotationStatus.IN_PROGRESS -> "In progress"
        AnnotationStatus.RESOLVED -> "Resolved"
    }
