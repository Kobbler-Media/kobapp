package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme

enum class KobChipStyle { Default, AccentTint, Ghost }

@Composable
fun KobChip(
    text: String,
    modifier: Modifier = Modifier,
    style: KobChipStyle = KobChipStyle.Default,
    accent: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = KobappTheme.colors
    val acc = accent ?: KobappTheme.accent
    val bg = when (style) {
        KobChipStyle.Default -> colors.elev2
        KobChipStyle.AccentTint -> lerp(colors.elev1, acc, 0.14f)
        KobChipStyle.Ghost -> Color.Transparent
    }
    val border = when (style) {
        KobChipStyle.Default -> colors.lineSoft
        KobChipStyle.AccentTint -> lerp(Color.Transparent, acc, 0.30f)
        KobChipStyle.Ghost -> colors.lineSoft
    }
    val fg = when (style) {
        KobChipStyle.AccentTint -> acc
        else -> colors.textMd
    }
    val shape = KobappTheme.shapes.pill
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .let {
                if (onClick != null) {
                    it.tvAutoGlow(cornerRadiusDp = 999f, scale = TvFocusScale.Subtle)
                        .onFocusChanged { state -> focused = state.isFocused }
                } else {
                    it
                }
            }
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            if (leading != null) leading()
            Text(
                text = text,
                style = KobappTheme.type.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.tvMarquee(focused),
            )
        }
    }
}
