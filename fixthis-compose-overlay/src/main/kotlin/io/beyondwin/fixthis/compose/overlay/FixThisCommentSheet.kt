package io.beyondwin.fixthis.compose.overlay

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.ScopeCandidate
import io.beyondwin.fixthis.compose.core.model.ScreenshotInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FixThisCommentSheet(
    draft: FixThisDraft,
    modifier: Modifier = Modifier,
    onCommentChanged: (String) -> Unit,
    onScopeSelected: (ScopeCandidate) -> Unit,
    onCopyForAi: () -> Unit,
    onCopyJson: () -> Unit,
    onShare: () -> Unit,
    onSendToAiAgent: () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Smart Select",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = draft.selectedSummary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (draft.scopeCandidates.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    draft.scopeCandidates.forEach { candidate ->
                        FilterChip(
                            selected = candidate.nodeUid == draft.selectedScopeNodeUid,
                            onClick = { onScopeSelected(candidate) },
                            label = {
                                Text(
                                    text = candidate.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = draft.userComment,
                onValueChange = onCommentChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
                label = { Text("Comment") },
                maxLines = 4,
            )

            ScreenshotCropPreview(screenshot = draft.screenshot)
            Text(
                text = ScreenshotWarningText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = if (draft.isAiAgentWaiting) onSendToAiAgent else onCopyForAi,
                    modifier = Modifier.sizeIn(minHeight = 40.dp),
                ) {
                    Text(
                        text = if (draft.isAiAgentWaiting) "Send to AI Agent" else "Copy for AI",
                        maxLines = 1,
                    )
                }
                OutlinedButton(
                    onClick = onCopyJson,
                    modifier = Modifier.sizeIn(minHeight = 40.dp),
                ) {
                    Text(text = "Copy JSON", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.sizeIn(minHeight = 40.dp),
                ) {
                    Text(text = "Share", maxLines = 1)
                }
            }
        }
    }
}

private const val ScreenshotWarningText = "Screenshots are saved locally. They may contain sensitive information."

@Composable
private fun ScreenshotCropPreview(screenshot: ScreenshotInfo?) {
    val cropPaths = screenshot.cropPreviewPathCandidates()
    val previewState by produceState<CropPreviewDecodeState>(
        initialValue = if (cropPaths.isEmpty()) {
            CropPreviewDecodeState.NoCrop
        } else {
            CropPreviewDecodeState.Loading(cropPaths)
        },
        cropPaths,
    ) {
        value = loadCropPreview(cropPaths)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Screenshot crop",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            if (cropPaths.isEmpty()) {
                Text(
                    text = "No screenshot crop",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ScreenshotCropPreviewContent(previewState = previewState)
                val dimensions = listOfNotNull(screenshot?.width, screenshot?.height)
                if (dimensions.size == 2) {
                    Text(
                        text = "${dimensions[0]} x ${dimensions[1]}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CropPathDebugText(previewState = previewState)
            }
        }
    }
}

@Composable
private fun ScreenshotCropPreviewContent(previewState: CropPreviewDecodeState) {
    when (previewState) {
        CropPreviewDecodeState.NoCrop -> Unit
        is CropPreviewDecodeState.Loading -> Text(
            text = "Loading screenshot crop",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is CropPreviewDecodeState.DecodeFailed -> Text(
            text = "Could not decode screenshot crop",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        is CropPreviewDecodeState.Success -> Image(
            bitmap = previewState.bitmap.asImageBitmap(),
            contentDescription = "Screenshot crop preview",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    ratio = previewState.bitmap.width.toFloat() /
                        previewState.bitmap.height.toFloat().coerceAtLeast(1f),
                    matchHeightConstraintsFirst = false,
                )
                .sizeIn(maxHeight = 220.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun CropPathDebugText(previewState: CropPreviewDecodeState) {
    val pathText = when (previewState) {
        CropPreviewDecodeState.NoCrop -> return
        is CropPreviewDecodeState.Loading -> previewState.paths.joinToString(separator = "\n")
        is CropPreviewDecodeState.DecodeFailed -> previewState.paths.joinToString(separator = "\n")
        is CropPreviewDecodeState.Success -> previewState.path
    }
    Text(
        text = pathText,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun ScreenshotInfo?.cropPreviewPathCandidates(): List<String> =
    listOfNotNull(this?.cropPath, this?.desktopCropPath).distinct()

internal fun calculateBitmapSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
    var sampleSize = 1
    while (width / (sampleSize * 2) >= maxDimension || height / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

private suspend fun loadCropPreview(paths: List<String>): CropPreviewDecodeState =
    withContext(Dispatchers.IO) {
        if (paths.isEmpty()) {
            CropPreviewDecodeState.NoCrop
        } else {
            for (path in paths) {
                val bitmap = decodeSampledCropBitmap(path, maxDimension = 720)
                if (bitmap != null) {
                    return@withContext CropPreviewDecodeState.Success(
                        path = path,
                        bitmap = bitmap,
                        attemptedPaths = paths,
                    )
                }
            }
            CropPreviewDecodeState.DecodeFailed(paths)
        }
    }

private fun decodeSampledCropBitmap(path: String, maxDimension: Int) =
    runCatching {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, boundsOptions)
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            return@runCatching null
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateBitmapSampleSize(
                width = boundsOptions.outWidth,
                height = boundsOptions.outHeight,
                maxDimension = maxDimension,
            )
        }
        BitmapFactory.decodeFile(path, decodeOptions)
    }.getOrNull()

private sealed interface CropPreviewDecodeState {
    data object NoCrop : CropPreviewDecodeState
    data class Loading(val paths: List<String>) : CropPreviewDecodeState
    data class Success(
        val path: String,
        val bitmap: android.graphics.Bitmap,
        val attemptedPaths: List<String>,
    ) : CropPreviewDecodeState
    data class DecodeFailed(val paths: List<String>) : CropPreviewDecodeState
}

@Preview(widthDp = 360)
@Composable
private fun FixThisCommentSheetPreview() {
    MaterialTheme {
        FixThisCommentSheet(
            draft = FixThisDraft(
                scopeCandidates = listOf(
                    ScopeCandidate(
                        label = "Checkout row",
                        nodeUid = "row",
                        boundsInWindow = FixThisRect(0f, 0f, 240f, 64f),
                        score = 0.9,
                    ),
                ),
                screenshot = ScreenshotInfo(cropPath = "/tmp/fixthis/crop.png", width = 240, height = 96),
                userComment = "Button label is truncated",
            ),
            onCommentChanged = {},
            onScopeSelected = {},
            onCopyForAi = {},
            onCopyJson = {},
            onShare = {},
            onSendToAiAgent = {},
        )
    }
}
