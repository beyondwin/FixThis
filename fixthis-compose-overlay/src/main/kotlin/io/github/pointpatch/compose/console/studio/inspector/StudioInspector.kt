package io.github.pointpatch.compose.console.studio.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.pointpatch.compose.console.studio.StudioViewModel
import io.github.pointpatch.compose.console.studio.common.leftBorder
import io.github.pointpatch.compose.console.studio.model.StudioTool
import io.github.pointpatch.compose.console.studio.theme.StudioColors

@Composable
internal fun StudioInspector(
    vm: StudioViewModel,
    modifier: Modifier = Modifier,
) {
    val selected = vm.selectedAnnotation
    Box(
        modifier = modifier
            .leftBorder(StudioColors.Line)
            .background(StudioColors.Bg1),
    ) {
        if (selected == null) {
            AnnotationsPanel(
                annotations = vm.annotations,
                onSelect = vm::selectAnnotation,
                onStartAnnotating = { vm.setTool(StudioTool.ANNOTATE) },
            )
        } else {
            AnnotationDetail(
                annotation = selected,
                annotationCount = vm.annotations.size,
                onBack = { vm.selectAnnotation(null) },
                onUpdate = { transform -> vm.updateAnnotation(selected.id, transform) },
                onDelete = {
                    vm.deleteAnnotation(selected.id)
                    vm.selectAnnotation(null)
                },
            )
        }
    }
}
