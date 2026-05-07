package io.beyondwin.fixthis.compose.console.studio.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.beyondwin.fixthis.compose.console.studio.theme.StudioColors

@Composable
internal fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) StudioColors.Bg2 else Color.Transparent,
        animationSpec = tween(120),
        label = "ghostBg",
    )
    val border by animateColorAsState(
        targetValue = if (hovered) StudioColors.Line else Color.Transparent,
        animationSpec = tween(120),
        label = "ghostBorder",
    )
    val color by animateColorAsState(
        targetValue = if (hovered) StudioColors.Txt0 else StudioColors.Txt1,
        animationSpec = tween(120),
        label = "ghostFg",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(7.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = StudioFontFamily,
        )
    }
}

@Composable
internal fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    enabled: Boolean = true,
    mini: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> StudioColors.Accent
            hovered -> StudioColors.AccentHover
            else -> StudioColors.Accent
        },
        animationSpec = tween(120),
        label = "primaryBg",
    )
    val translate by animateDpAsState(
        targetValue = if (hovered && enabled) (-1).dp else 0.dp,
        animationSpec = tween(120),
        label = "primaryTranslate",
    )
    val alpha = if (enabled) 1f else 0.4f

    Row(
        modifier = modifier
            .offset { IntOffset(x = 0, y = translate.roundToPx()) }
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .alpha(alpha)
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(
                horizontal = if (mini) 10.dp else 14.dp,
                vertical = if (mini) 5.dp else 7.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (glyph != null) {
            Text(
                text = glyph,
                color = StudioColors.Bg0,
                fontSize = 11.sp,
                modifier = Modifier.alpha(0.7f),
                fontFamily = StudioFontFamily,
            )
        }
        Text(
            text = label,
            color = StudioColors.Bg0,
            fontSize = if (mini) 11.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = StudioFontFamily,
        )
    }
}

@Composable
internal fun DangerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) StudioColors.Danger.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = tween(120),
        label = "dangerBg",
    )
    val border by animateColorAsState(
        targetValue = if (hovered) StudioColors.Danger.copy(alpha = 0.30f) else Color.Transparent,
        animationSpec = tween(120),
        label = "dangerBorder",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(7.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = StudioColors.Danger,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = StudioFontFamily,
        )
    }
}
