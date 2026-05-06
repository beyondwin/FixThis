package io.github.pointpatch.compose.console.studio.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Immutable
internal data class StudioShapes(
    val xs: Shape = RoundedCornerShape(4.dp),
    val sm: Shape = RoundedCornerShape(6.dp),
    val md: Shape = RoundedCornerShape(7.dp),
    val lg: Shape = RoundedCornerShape(8.dp),
    val xl: Shape = RoundedCornerShape(12.dp),
    val pill: Shape = RoundedCornerShape(percent = 50),
)
