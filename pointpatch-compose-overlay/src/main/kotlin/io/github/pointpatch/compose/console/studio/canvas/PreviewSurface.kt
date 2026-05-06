package io.github.pointpatch.compose.console.studio.canvas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pointpatch.compose.console.studio.StudioViewModel
import io.github.pointpatch.compose.console.studio.common.StudioFontFamily
import io.github.pointpatch.compose.console.studio.model.RectPercent
import io.github.pointpatch.compose.console.studio.model.StudioTool
import io.github.pointpatch.compose.core.model.ScreenshotInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal val LocalPreviewSizePx = compositionLocalOf { IntSize.Zero }
private const val PreviewBitmapMaxDimension = 1600

@Composable
internal fun PreviewSurface(
    vm: StudioViewModel,
    previewState: FullPreviewDecodeState,
    interactionMode: CanvasInteractionMode,
    modifier: Modifier = Modifier,
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val registry = remember(interactionMode == CanvasInteractionMode.WidgetSnapAndRegion) { WidgetRegistry() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { sizePx = it }
            .onGloballyPositioned { registry.updateSurfaceCoordinates(it) }
            .annotateDragGestures(
                tool = vm.tool,
                interactionMode = interactionMode,
                widgetRegistry = registry,
                previewSize = sizePx,
                onBegin = { percent, widget, widgetBounds ->
                    vm.beginDrag(
                        percent = percent,
                        widgetTag = widget,
                        widgetBoundsPercent = widgetBounds,
                    )
                },
                onUpdate = { percent -> vm.updateDrag(percent) },
                onEnd = vm::endDrag,
                onCancel = vm::cancelDrag,
                onSelectEmpty = { vm.selectAnnotation(null) },
            ),
    ) {
        CompositionLocalProvider(
            LocalPreviewSizePx provides sizePx,
            LocalWidgetRegistry provides registry,
        ) {
            PreviewScreenshotContent(
                previewState = previewState,
                tool = vm.tool,
                modifier = Modifier.fillMaxSize(),
            )

            vm.annotations.forEachIndexed { index, annotation ->
                PinRect(
                    annotation = annotation,
                    index = index,
                    isSelected = annotation.id == vm.selectedId,
                    enabled = vm.tool == StudioTool.SELECT,
                    onClick = { vm.selectAnnotation(annotation.id) },
                )
            }

            if (vm.dragMoved) {
                vm.draggingRect?.let { rect -> DragRect(rect = rect) }
            }
        }
    }
}

@Composable
private fun PreviewScreenshotContent(
    previewState: FullPreviewDecodeState,
    tool: StudioTool,
    modifier: Modifier = Modifier,
) {
    when (previewState) {
        FullPreviewDecodeState.NoScreenshot -> DevicePreviewContent(tool = tool, modifier = modifier)
        is FullPreviewDecodeState.Loading -> PreviewMessage(
            text = "Loading screenshot preview",
            modifier = modifier,
        )

        is FullPreviewDecodeState.DecodeFailed -> PreviewMessage(
            text = "Could not decode screenshot preview",
            modifier = modifier,
        )

        is FullPreviewDecodeState.Success -> Image(
            bitmap = previewState.bitmap.asImageBitmap(),
            contentDescription = "Screenshot preview",
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun PreviewMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color(0xFFF6F7F9)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color(0xFF4B5563),
            fontSize = 13.sp,
            fontFamily = StudioFontFamily,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

internal fun Modifier.annotateDragGestures(
    tool: StudioTool,
    interactionMode: CanvasInteractionMode,
    widgetRegistry: WidgetRegistry,
    previewSize: IntSize,
    onBegin: (androidx.compose.ui.geometry.Offset, String?, RectPercent?) -> Unit,
    onUpdate: (androidx.compose.ui.geometry.Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    onSelectEmpty: () -> Unit,
): Modifier =
    pointerInput(tool, interactionMode, previewSize, widgetRegistry) {
        if (tool != StudioTool.ANNOTATE || interactionMode == CanvasInteractionMode.AnnotationUnavailable) {
            detectTapGestures { onSelectEmpty() }
            return@pointerInput
        }
        detectAnnotateDrag(
            widgetRegistry = widgetRegistry.takeIf {
                interactionMode == CanvasInteractionMode.WidgetSnapAndRegion
            },
            previewSize = previewSize,
            onBegin = onBegin,
            onUpdate = onUpdate,
            onEnd = onEnd,
            onCancel = onCancel,
        )
    }

private suspend fun PointerInputScope.detectAnnotateDrag(
    widgetRegistry: WidgetRegistry?,
    previewSize: IntSize,
    onBegin: (androidx.compose.ui.geometry.Offset, String?, RectPercent?) -> Unit,
    onUpdate: (androidx.compose.ui.geometry.Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val widget = widgetRegistry?.hitTest(down.position)
        onBegin(
            offsetToPercent(down.position, previewSize),
            widget?.tag,
            widget?.boundsInSurface?.let { rectToPercentBounds(it, previewSize) },
        )
        try {
            val completed = drag(down.id) { change ->
                onUpdate(offsetToPercent(change.position, previewSize))
                change.consume()
            }
            if (completed) {
                onEnd()
            } else {
                onCancel()
                return@awaitEachGesture
            }
        } catch (cancellation: CancellationException) {
            onCancel()
            throw cancellation
        }
    }
}

internal fun ScreenshotInfo?.fullPreviewPathCandidates(): List<String> =
    listOfNotNull(this?.fullPath, this?.desktopFullPath)
        .filter { it.isNotBlank() }
        .distinct()

internal fun calculatePreviewBitmapSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
    var sampleSize = 1
    while (width / (sampleSize * 2) >= maxDimension || height / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

internal suspend fun loadFullPreview(
    paths: List<String>,
    hasScreenshotMetadata: Boolean = false,
    onSuccess: (FullPreviewDecodeState.Success) -> Unit,
): FullPreviewDecodeState? =
    withContext(Dispatchers.IO) {
        if (paths.isEmpty()) {
            if (hasScreenshotMetadata) {
                FullPreviewDecodeState.DecodeFailed(paths)
            } else {
                FullPreviewDecodeState.NoScreenshot
            }
        } else {
            for (path in paths) {
                var bitmap = decodeSampledPreviewBitmap(path, maxDimension = PreviewBitmapMaxDimension)
                if (bitmap != null) {
                    try {
                        currentCoroutineContext().ensureActive()
                        val success = FullPreviewDecodeState.Success(
                            path = path,
                            bitmap = bitmap,
                            attemptedPaths = paths,
                        )
                        onSuccess(success)
                        bitmap = null
                        return@withContext null
                    } finally {
                        bitmap?.recycleIfNeeded()
                    }
                }
            }
            FullPreviewDecodeState.DecodeFailed(paths)
        }
    }

private fun decodeSampledPreviewBitmap(path: String, maxDimension: Int): Bitmap? =
    runCatching {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, boundsOptions)
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            return@runCatching null
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculatePreviewBitmapSampleSize(
                width = boundsOptions.outWidth,
                height = boundsOptions.outHeight,
                maxDimension = maxDimension,
            )
        }
        BitmapFactory.decodeFile(path, decodeOptions)
    }.getOrNull()

internal sealed interface FullPreviewDecodeState {
    val decodeStatus: PreviewScreenshotDecodeStatus

    data object NoScreenshot : FullPreviewDecodeState {
        override val decodeStatus = PreviewScreenshotDecodeStatus.NoScreenshot
    }

    data class Loading(val paths: List<String>) : FullPreviewDecodeState {
        override val decodeStatus = PreviewScreenshotDecodeStatus.Loading
    }

    data class Success(
        val path: String,
        val bitmap: Bitmap,
        val attemptedPaths: List<String>,
    ) : FullPreviewDecodeState {
        override val decodeStatus = PreviewScreenshotDecodeStatus.DecodedScreenshot
    }

    data class DecodeFailed(val paths: List<String>) : FullPreviewDecodeState {
        override val decodeStatus = PreviewScreenshotDecodeStatus.DecodeFailed
    }
}

internal enum class PreviewScreenshotDecodeStatus {
    NoScreenshot,
    Loading,
    DecodedScreenshot,
    DecodeFailed,
}

internal enum class CanvasInteractionMode {
    WidgetSnapAndRegion,
    RegionOnly,
    AnnotationUnavailable,
}

internal fun initialFullPreviewDecodeState(
    paths: List<String>,
    hasScreenshotMetadata: Boolean = false,
): FullPreviewDecodeState =
    if (paths.isEmpty()) {
        if (hasScreenshotMetadata) {
            FullPreviewDecodeState.DecodeFailed(paths)
        } else {
            FullPreviewDecodeState.NoScreenshot
        }
    } else {
        FullPreviewDecodeState.Loading(paths)
    }

internal fun previewInteractionMode(status: PreviewScreenshotDecodeStatus): CanvasInteractionMode =
    when (status) {
        PreviewScreenshotDecodeStatus.NoScreenshot -> CanvasInteractionMode.WidgetSnapAndRegion
        PreviewScreenshotDecodeStatus.DecodedScreenshot -> CanvasInteractionMode.RegionOnly
        PreviewScreenshotDecodeStatus.Loading,
        PreviewScreenshotDecodeStatus.DecodeFailed,
        -> CanvasInteractionMode.AnnotationUnavailable
    }

internal fun FullPreviewDecodeState.recycleSuccessBitmap() {
    if (this is FullPreviewDecodeState.Success) {
        bitmap.recycleIfNeeded()
    }
}

private fun Bitmap.recycleIfNeeded() {
    if (!isRecycled) {
        recycle()
    }
}

@Composable
internal fun DevicePreviewContent(
    tool: StudioTool,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFF8FAFC), Color(0xFFFFFFFF)),
                ),
            )
            .padding(horizontal = 22.dp),
    ) {
        Spacer(modifier = Modifier.height(54.dp))
        StatusRow()
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "Good morning, Mina",
            color = Color(0xFF18202A),
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = StudioFontFamily,
            modifier = Modifier
                .fillMaxWidth()
                .studioWidget("greeting", tool),
        )
        Spacer(modifier = Modifier.height(18.dp))
        BalanceCard(tool = tool)
        Spacer(modifier = Modifier.height(18.dp))
        ActionRow(tool = tool)
        Spacer(modifier = Modifier.height(24.dp))
        ActivityList(tool = tool)
    }
}

@Composable
private fun StatusRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "9:41",
            color = Color(0xFF111827),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = StudioFontFamily,
        )
        Spacer(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = (6 + it * 2).dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF111827)),
                )
            }
            Box(
                modifier = Modifier
                    .width(20.dp)
                    .height(9.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF111827)),
            )
        }
    }
}

@Composable
private fun BalanceCard(tool: StudioTool) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF151A23), Color(0xFF334155)),
                ),
            )
            .studioWidget("balance-card", tool)
            .padding(22.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Total balance",
            color = Color.White.copy(alpha = 0.68f),
            fontSize = 13.sp,
            fontFamily = StudioFontFamily,
            modifier = Modifier.studioWidget("balance-title", tool),
        )
        Text(
            text = "$12,482.90",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = StudioFontFamily,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "+2.4%",
                color = Color(0xFFB8D36A),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = StudioFontFamily,
            )
            Text(
                text = " this month",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 13.sp,
                fontFamily = StudioFontFamily,
            )
        }
    }
}

@Composable
private fun ActionRow(tool: StudioTool) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ActionButton(label = "Send", tag = "action-send", color = Color(0xFFEFF6FF), tool = tool)
        ActionButton(label = "Request", tag = "action-request", color = Color(0xFFF0FDF4), tool = tool)
        ActionButton(label = "Cards", tag = "action-cards", color = Color(0xFFFFFBEB), tool = tool)
    }
}

@Composable
private fun RowScope.ActionButton(
    label: String,
    tag: String,
    color: Color,
    tool: StudioTool,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .height(82.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(color)
            .studioWidget(tag, tool)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(0xFF111827)),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = Color(0xFF111827),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = StudioFontFamily,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ActivityList(tool: StudioTool) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Recent activity",
            color = Color(0xFF111827),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = StudioFontFamily,
            modifier = Modifier.studioWidget("activity-title", tool),
        )
        ActivityRow("Coffee House", "-$6.40", "activity-coffee", tool)
        ActivityRow("Payroll", "+$3,200.00", "activity-payroll", tool)
        ActivityRow("Metro pass", "-$42.00", "activity-metro", tool)
    }
}

@Composable
private fun ActivityRow(
    title: String,
    amount: String,
    tag: String,
    tool: StudioTool,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF3F4F6))
            .studioWidget(tag, tool)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            color = Color(0xFF111827),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = StudioFontFamily,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = amount,
            color = Color(0xFF334155),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = StudioFontFamily,
        )
    }
}
