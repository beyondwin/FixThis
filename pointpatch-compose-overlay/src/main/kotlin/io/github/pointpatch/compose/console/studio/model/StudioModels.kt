package io.github.pointpatch.compose.console.studio.model

import androidx.compose.runtime.Immutable

internal enum class Severity {
    HIGH,
    MED,
    LOW,
}

internal enum class AnnotationStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
}

internal enum class StudioTool {
    SELECT,
    ANNOTATE,
}

internal fun Severity.wireValue(): String =
    when (this) {
        Severity.HIGH -> "high"
        Severity.MED -> "med"
        Severity.LOW -> "low"
    }

internal fun AnnotationStatus.wireValue(): String =
    when (this) {
        AnnotationStatus.OPEN -> "open"
        AnnotationStatus.IN_PROGRESS -> "in-progress"
        AnnotationStatus.RESOLVED -> "resolved"
    }

@Immutable
internal data class Annotation(
    val id: String,
    val label: String,
    val severity: Severity,
    val status: AnnotationStatus,
    val comment: String,
    val rectPercent: RectPercent,
)

@Immutable
internal data class RectPercent(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

@Immutable
internal data class Snapshot(
    val id: String,
    val title: String,
    val author: String,
    val createdAtEpochMillis: Long,
    val annotations: List<Annotation>,
)

@Immutable
internal data class StudioState(
    val snapshots: List<Snapshot>,
    val activeSnapId: String?,
    val annotations: List<Annotation>,
    val draftTitle: String,
    val selectedId: String?,
    val tool: StudioTool,
    val draggingRect: RectPercent?,
    val dragMoved: Boolean,
)
