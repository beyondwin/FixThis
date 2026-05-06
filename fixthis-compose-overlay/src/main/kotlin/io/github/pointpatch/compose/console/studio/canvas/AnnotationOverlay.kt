package io.github.pointpatch.compose.console.studio.canvas

import androidx.compose.runtime.Composable
import io.github.pointpatch.compose.console.studio.model.Annotation

@Composable
internal fun AnnotationOverlay(
    annotations: List<Annotation>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    annotations.forEachIndexed { index, annotation ->
        PinRect(
            annotation = annotation,
            index = index,
            isSelected = annotation.id == selectedId,
            enabled = enabled,
            onClick = { onSelect(annotation.id) },
        )
    }
}
