package io.github.pointpatch.compose.console.studio.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun PhoneFrame(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val bezelShape = RoundedCornerShape(44.dp)
    val screenShape = RoundedCornerShape(36.dp)

    Box(
        modifier = modifier
            .size(width = 320.dp, height = 660.dp)
            .shadow(elevation = 52.dp, shape = bezelShape, clip = false)
            .shadow(elevation = 30.dp, shape = bezelShape, clip = false)
            .clip(bezelShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF2A2A2E), Color(0xFF1A1A1D)),
                ),
            )
            .border(2.dp, Color(0xFF3A3A40), bezelShape)
            .drawBehind {
                drawRect(
                    color = Color.White.copy(alpha = 0.06f),
                    topLeft = Offset(x = 44.dp.toPx(), y = 1.dp.toPx()),
                    size = Size(width = size.width - 88.dp.toPx(), height = 1.dp.toPx()),
                )
            }
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(screenShape)
                .background(Color.White)
                .matchParentSize(),
        ) {
            content()
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .size(width = 86.dp, height = 22.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black),
            )
        }
    }
}
