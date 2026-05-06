package io.github.pointpatch.compose.console.studio.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pointpatch.compose.console.studio.StudioTestTags
import io.github.pointpatch.compose.console.studio.common.PanelHead
import io.github.pointpatch.compose.console.studio.common.PrimaryButton
import io.github.pointpatch.compose.console.studio.common.StudioFontFamily
import io.github.pointpatch.compose.console.studio.model.Annotation
import io.github.pointpatch.compose.console.studio.theme.StudioColors

@Composable
internal fun AnnotationsPanel(
    annotations: List<Annotation>,
    onSelect: (String) -> Unit,
    onStartAnnotating: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PanelHead(title = "Annotations", count = annotations.size)
        if (annotations.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(onStartAnnotating = onStartAnnotating)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(items = annotations, key = { _, ann -> ann.id }) { index, ann ->
                    AnnotationRow(
                        annotation = ann,
                        index = index,
                        onClick = { onSelect(ann.id) },
                        modifier = Modifier.testTag(StudioTestTags.annotationRow(index)),
                    )
                }
            }
        }
    }
}

@Composable
internal fun EmptyState(
    onStartAnnotating: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(StudioColors.Bg2)
                .dashedMarkBorder()
                .padding(bottom = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⌘",
                color = StudioColors.Txt2,
                fontSize = 18.sp,
                fontFamily = StudioFontFamily,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "No annotations yet",
            color = StudioColors.Txt0,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = StudioFontFamily,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = buildAnnotatedString {
                append("Switch to ")
                withStyle(SpanStyle(color = StudioColors.Txt0)) {
                    append("Annotate")
                }
                append(", then click a widget or drag a region on the preview.")
            },
            color = StudioColors.Txt1,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontFamily = StudioFontFamily,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 220.dp),
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            label = "Start annotating",
            mini = true,
            onClick = onStartAnnotating,
            modifier = Modifier.testTag(StudioTestTags.EmptyStartAnnotating),
        )
    }
}

private fun Modifier.dashedMarkBorder(): Modifier =
    drawBehind {
        val stroke = 1.dp.toPx()
        drawRoundRect(
            color = StudioColors.Line,
            style = Stroke(
                width = stroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            ),
            cornerRadius = CornerRadius(12.dp.toPx()),
        )
    }
