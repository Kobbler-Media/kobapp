package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme

enum class KobButtonStyle { Default, Primary, Accent, Ghost }
enum class KobButtonSize { Default, Prominent, TvHero, Icon }

@Composable
private fun kobButtonTextStyle(size: KobButtonSize): TextStyle = when (size) {
    KobButtonSize.Default -> KobappTheme.type.bodySm
    KobButtonSize.Prominent -> KobappTheme.type.body
    KobButtonSize.TvHero -> KobappTheme.type.h3
    KobButtonSize.Icon -> KobappTheme.type.bodySm
}

@Composable
fun KobButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KobButtonStyle = KobButtonStyle.Default,
    size: KobButtonSize = KobButtonSize.Default,
    enabled: Boolean = true,
    accent: Color? = null,
    cornerRadiusDp: Float = 10f,
    content: @Composable () -> Unit,
) {
    val colors = KobappTheme.colors
    val acc = accent ?: KobappTheme.accent
    val hPad: Dp; val vPad: Dp
    when (size) {
        KobButtonSize.Default -> { hPad = 16.dp; vPad = 10.dp }
        KobButtonSize.Prominent -> { hPad = 22.dp; vPad = 14.dp }
        KobButtonSize.TvHero -> { hPad = 32.dp; vPad = 20.dp }
        // Sized by its caller: a text button's padding eats most of a small fixed box.
        KobButtonSize.Icon -> { hPad = 0.dp; vPad = 0.dp }
    }
    val bg = when (style) {
        KobButtonStyle.Default -> colors.elev2
        KobButtonStyle.Primary -> colors.textHi
        KobButtonStyle.Accent -> acc
        KobButtonStyle.Ghost -> Color.Transparent
    }
    val fg = when (style) {
        KobButtonStyle.Default -> colors.textHi
        KobButtonStyle.Primary -> colors.bg
        KobButtonStyle.Accent -> colors.bg
        KobButtonStyle.Ghost -> colors.textHi
    }
    val borderColor = when (style) {
        KobButtonStyle.Default -> colors.line
        else -> Color.Transparent
    }
    val shape = RoundedCornerShape(cornerRadiusDp.dp)
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    // onFocusChanged is the authoritative signal: the interaction source only reports
    // focus the clickable itself took, and misses focus delegated in from a parent
    // focusGroup — which is how entry focus arrives on TV.
    var hasFocus by remember { mutableStateOf(false) }
    val lit = isFocused || hasFocus
    val focusProgress = tvFocusProgress(lit)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (size == KobButtonSize.Icon) {
            Arrangement.Center
        } else {
            Arrangement.spacedBy(8.dp)
        },
        modifier = modifier
            .onFocusChanged { hasFocus = it.isFocused }
            // Ring drawing has to come before .clip so the stroke that sits
            // outside the button shape isn't clipped away.
            .kobFocusRing(focusProgress, cornerRadiusDp = cornerRadiusDp, accent = acc)
            .clip(shape)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .let { if (style == KobButtonStyle.Default) it.border(1.dp, borderColor, shape) else it }
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            // The touch-target floor has to sit outside the padding: inside it, the
            // floor applies to the content box and the padding stacks on top of it,
            // which is 20dp of dead height on every default-size button.
            .defaultMinSize(minHeight = KobappTheme.dimens.minTouchTarget)
            .padding(PaddingValues(horizontal = hPad, vertical = vPad)),
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            content()
        }
    }
}

@Composable
fun KobButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KobButtonStyle = KobButtonStyle.Default,
    size: KobButtonSize = KobButtonSize.Default,
    enabled: Boolean = true,
    accent: Color? = null,
    cornerRadiusDp: Float = 10f,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    KobButton(
        onClick = onClick,
        modifier = modifier,
        style = style,
        size = size,
        enabled = enabled,
        accent = accent,
        cornerRadiusDp = cornerRadiusDp,
    ) {
        if (leading != null) leading()
        Text(text = text, style = kobButtonTextStyle(size))
        if (trailing != null) trailing()
    }
}
