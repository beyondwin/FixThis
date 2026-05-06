package io.github.pointpatch.compose.console.studio.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
internal data class StudioElevation(
    val none: Dp = 0.dp,
    val flat: Dp = 1.dp,
    val raised: Dp = 4.dp,
    val overlay: Dp = 12.dp,
)
