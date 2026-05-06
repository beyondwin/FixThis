package io.github.pointpatch.compose.console.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import io.github.pointpatch.compose.console.studio.model.Annotation
import io.github.pointpatch.compose.console.studio.model.AnnotationStatus
import io.github.pointpatch.compose.console.studio.model.RectPercent
import io.github.pointpatch.compose.console.studio.model.Severity
import io.github.pointpatch.compose.console.studio.model.Snapshot
import io.github.pointpatch.compose.console.studio.model.StudioState
import io.github.pointpatch.compose.console.studio.model.StudioTool
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

internal class StudioViewModel(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val author: String = "you",
    initialAnnotations: List<Annotation> = emptyList(),
) : ViewModel() {
    val snapshots: SnapshotStateList<Snapshot> = mutableStateListOf()
    var activeSnapId: String? by mutableStateOf(null)
        private set

    val annotations: SnapshotStateList<Annotation> = mutableStateListOf()
    private var mutableDraftTitle: String by mutableStateOf("New session")
    val draftTitle: String
        get() = mutableDraftTitle
    var selectedId: String? by mutableStateOf(null)
        private set

    private var mutableTool: StudioTool by mutableStateOf(StudioTool.SELECT)
    val tool: StudioTool
        get() = mutableTool
    var draggingRect: RectPercent? by mutableStateOf(null)
        private set
    var dragMoved: Boolean by mutableStateOf(false)
        private set

    private var dragStart: DragStart? = null

    init {
        annotations.addAll(initialAnnotations)
    }

    val openCount: Int
        get() = annotations.count { it.status == AnnotationStatus.OPEN }
    val resolvedCount: Int
        get() = annotations.count { it.status == AnnotationStatus.RESOLVED }
    val inProgressCount: Int
        get() = annotations.count { it.status == AnnotationStatus.IN_PROGRESS }
    val canSaveSnapshot: Boolean
        get() = annotations.isNotEmpty()
    val selectedAnnotation: Annotation?
        get() = annotations.firstOrNull { it.id == selectedId }
    val historyCount: Int
        get() = snapshots.size
    val annotationsCount: Int
        get() = annotations.size
    val state: StudioState
        get() = StudioState(
            snapshots = snapshots.toList(),
            activeSnapId = activeSnapId,
            annotations = annotations.toList(),
            draftTitle = draftTitle,
            selectedId = selectedId,
            tool = tool,
            draggingRect = draggingRect,
            dragMoved = dragMoved,
        )

    fun setTool(next: StudioTool) {
        mutableTool = next
    }

    fun setDraftTitle(next: String) {
        mutableDraftTitle = next
    }

    fun selectAnnotation(id: String?) {
        selectedId = id
    }

    fun openSnapshot(id: String) {
        val snap = snapshots.firstOrNull { it.id == id } ?: return
        activeSnapId = snap.id
        annotations.clear()
        annotations.addAll(snap.annotations)
        mutableDraftTitle = snap.title
        selectedId = null
    }

    fun deleteSnapshot(id: String) {
        val index = snapshots.indexOfFirst { it.id == id }
        if (index < 0) return

        val wasActive = activeSnapId == id
        snapshots.removeAt(index)
        if (wasActive) {
            val next = snapshots.firstOrNull()
            if (next != null) {
                openSnapshot(next.id)
            } else {
                newSession()
            }
        }
    }

    fun saveSnapshot() {
        if (annotations.isEmpty()) return
        val snap = Snapshot(
            id = idFactory(),
            title = draftTitle,
            author = author,
            createdAtEpochMillis = clock(),
            annotations = annotations.toList(),
        )
        snapshots.add(0, snap)
        activeSnapId = snap.id
    }

    fun newSession() {
        activeSnapId = null
        annotations.clear()
        mutableDraftTitle = "New session"
        selectedId = null
        mutableTool = StudioTool.SELECT
        resetDrag()
    }

    fun updateAnnotation(id: String, transform: (Annotation) -> Annotation) {
        val index = annotations.indexOfFirst { it.id == id }
        if (index < 0) return
        annotations[index] = transform(annotations[index])
    }

    fun deleteAnnotation(id: String) {
        annotations.removeAll { it.id == id }
        if (selectedId == id) selectedId = null
    }

    fun beginDrag(
        percent: Offset,
        widgetTag: String?,
        widgetBoundsPercent: RectPercent? = null,
    ) {
        if (tool != StudioTool.ANNOTATE) return
        val clamped = percent.coerceToPreviewPercent()
        dragStart = DragStart(
            percent = clamped,
            widgetTag = widgetTag,
            widgetBoundsPercent = widgetBoundsPercent?.coerceToPreviewPercent(),
        )
        dragMoved = false
        draggingRect = RectPercent(clamped.x, clamped.y, 0f, 0f)
        selectedId = null
    }

    fun updateDrag(percent: Offset) {
        val start = dragStart ?: return
        val clamped = percent.coerceToPreviewPercent()
        val dx = abs(clamped.x - start.percent.x)
        val dy = abs(clamped.y - start.percent.y)
        if (dx > 0.6f || dy > 0.6f) dragMoved = true

        draggingRect = RectPercent(
            x = min(start.percent.x, clamped.x),
            y = min(start.percent.y, clamped.y),
            w = dx,
            h = dy,
        )
    }

    fun endDrag() {
        val start = dragStart ?: return
        val rect = draggingRect
        val createdRegion = dragMoved && rect != null && rect.w > 1.5f && rect.h > 1.5f
        val newAnnotation = when {
            createdRegion -> Annotation(
                id = idFactory(),
                label = "Region ${annotations.size + 1}",
                severity = Severity.MED,
                status = AnnotationStatus.OPEN,
                comment = "",
                rectPercent = rect,
            )
            !createdRegion && start.widgetTag != null -> Annotation(
                id = idFactory(),
                label = humanizeWidgetTag(start.widgetTag),
                severity = Severity.MED,
                status = AnnotationStatus.OPEN,
                comment = "",
                rectPercent = start.widgetBoundsPercent
                    ?: rect.takeIf { it != null && it.w > 1.5f && it.h > 1.5f }
                    ?: RectPercent(start.percent.x, start.percent.y, 6f, 6f),
            )
            else -> null
        }

        if (newAnnotation != null) {
            annotations.add(newAnnotation)
            selectedId = newAnnotation.id
            mutableTool = StudioTool.SELECT
        }
        resetDrag()
    }

    fun cancelDrag() {
        resetDrag()
    }

    private fun resetDrag() {
        dragStart = null
        draggingRect = null
        dragMoved = false
    }

    private data class DragStart(
        val percent: Offset,
        val widgetTag: String?,
        val widgetBoundsPercent: RectPercent?,
    )
}

private fun humanizeWidgetTag(tag: String): String =
    tag.replace('-', ' ').replaceFirstChar { it.uppercase() }

private fun Offset.coerceToPreviewPercent(): Offset =
    Offset(
        x = x.coerceIn(0f, 100f),
        y = y.coerceIn(0f, 100f),
    )

private fun RectPercent.coerceToPreviewPercent(): RectPercent {
    val left = x.coerceIn(0f, 100f)
    val top = y.coerceIn(0f, 100f)
    val right = (x + w).coerceIn(0f, 100f)
    val bottom = (y + h).coerceIn(0f, 100f)
    return RectPercent(
        x = minOf(left, right),
        y = minOf(top, bottom),
        w = abs(right - left),
        h = abs(bottom - top),
    )
}
