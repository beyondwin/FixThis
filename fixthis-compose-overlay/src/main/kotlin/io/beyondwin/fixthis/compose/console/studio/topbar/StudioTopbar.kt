package io.beyondwin.fixthis.compose.console.studio.topbar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.beyondwin.fixthis.compose.console.studio.common.GhostButton
import io.beyondwin.fixthis.compose.console.studio.common.PrimaryButton
import io.beyondwin.fixthis.compose.console.studio.common.StudioFontFamily
import io.beyondwin.fixthis.compose.console.studio.common.StudioType
import io.beyondwin.fixthis.compose.console.studio.common.bottomBorder
import io.beyondwin.fixthis.compose.console.studio.theme.StudioColors

@Composable
internal fun StudioTopbar(
    draftTitle: String,
    onDraftTitleChange: (String) -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(StudioColors.Bg1)
            .bottomBorder(StudioColors.Line)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandMark(modifier = Modifier.width(220.dp))
        Breadcrumb(
            draftTitle = draftTitle,
            onDraftTitleChange = onDraftTitleChange,
            modifier = Modifier.weight(1f),
        )
        TopbarActions(
            canSave = canSave,
            onSave = onSave,
            onNewSession = onNewSession,
        )
    }
}

@Composable
internal fun BrandMark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(StudioColors.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "P",
                color = StudioColors.Bg0,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                fontFamily = StudioFontFamily,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "FixThis",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.01).em,
                color = StudioColors.Txt0,
                fontFamily = StudioFontFamily,
            )
            Text(text = "STUDIO", style = StudioType.PanelTitle)
        }
    }
}

@Composable
internal fun Breadcrumb(
    draftTitle: String,
    onDraftTitleChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val isHovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (isFocused || isHovered) StudioColors.Bg2 else Color.Transparent,
        animationSpec = tween(120),
        label = "bcInputBg",
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Console",
            color = StudioColors.Txt1,
            fontSize = 12.sp,
            fontFamily = StudioFontFamily,
            maxLines = 1,
        )
        Text(text = "/", color = StudioColors.Txt2, fontSize = 12.sp, fontFamily = StudioFontFamily)
        BasicTextField(
            value = draftTitle,
            onValueChange = onDraftTitleChange,
            singleLine = true,
            textStyle = StudioType.Base.copy(fontSize = 13.sp, color = StudioColors.Txt0),
            cursorBrush = SolidColor(StudioColors.Accent),
            interactionSource = interaction,
            modifier = Modifier
                .widthIn(min = 200.dp)
                .background(bg, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .hoverable(interaction),
        )
    }
}

@Composable
internal fun TopbarActions(
    canSave: Boolean,
    onSave: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostButton(label = "New session", onClick = onNewSession)
        PrimaryButton(label = "Save snapshot", glyph = "⌘", enabled = canSave, onClick = onSave)
    }
}
