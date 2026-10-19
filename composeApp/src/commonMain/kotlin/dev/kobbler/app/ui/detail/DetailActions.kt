package dev.kobbler.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import dev.kobbler.kobfocus.focusKeyGroup
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle

private const val PillRadius = 24f

/**
 * The one action anyone opened the page for. How far in you are rides inside it, so
 * Resume needs no second line and no free-floating bar beneath the row.
 */
@Composable
internal fun PrimaryAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    detail: String? = null,
    progress: Float? = null,
    remaining: String? = null,
    emphasised: Boolean = true,
) {
    val colors = KobappTheme.colors
    val fg = if (emphasised) colors.bg else colors.textHi
    KobButton(
        onClick = onClick,
        style = if (emphasised) KobButtonStyle.Accent else KobButtonStyle.Default,
        accent = colors.action,
        enabled = enabled,
        cornerRadiusDp = PillRadius,
        modifier = modifier,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
        // Two short lines rather than one long one: the pill stays narrow enough to share
        // a phone's row with the icons and still say how much is left.
        Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
                if (detail != null) {
                    Text(detail, style = KobappTheme.type.body, color = fg.copy(alpha = 0.72f), maxLines = 1)
                }
            }
            if (progress != null || remaining != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (progress != null) {
                        Box(
                            Modifier
                                .weight(1f)
                                .widthIn(min = 56.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(fg.copy(alpha = 0.3f)),
                        ) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(fg))
                        }
                    }
                    if (remaining != null) {
                        Text(remaining, style = KobappTheme.type.caption, color = fg.copy(alpha = 0.72f), maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * A secondary action as a round icon, named by a caption that floats beneath it while it
 * is focused or hovered. Labelled in place, four of these pushed Resume's row onto a
 * second line; opening out on focus re-flowed the row on every keypress; unlabelled, a
 * remote user is left guessing at glyphs. The caption takes no layout space, so focus
 * moves nothing. Touch has neither state, so a phone keeps the description alone.
 */
@Composable
internal fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    tint: Color = KobappTheme.colors.textHi,
    style: KobButtonStyle = KobButtonStyle.Default,
    captioned: Boolean = true,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val showCaption = captioned && LocalFormFactor.current != FormFactor.Phone && (focused || hovered)
    val captionAlpha by animateFloatAsState(if (showCaption) 1f else 0f)
    Layout(
        content = {
            KobButton(
                onClick = onClick,
                style = style,
                accent = accent,
                enabled = enabled,
                cornerRadiusDp = PillRadius,
                modifier = modifier
                    .onFocusChanged { focused = it.isFocused }
                    .hoverable(hover)
                    .semantics { contentDescription = label },
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            if (captioned) {
                Text(
                    label,
                    style = KobappTheme.type.bodySm,
                    color = KobappTheme.colors.textMd,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = captionAlpha }.clearAndSetSemantics { },
                )
            }
        },
    ) { measurables, constraints ->
        val button = measurables[0].measure(constraints)
        val caption = measurables.getOrNull(1)?.measure(Constraints())
        val gap = CaptionGap.roundToPx()
        layout(button.width, button.height) {
            button.place(0, 0)
            caption?.place((button.width - caption.width) / 2, button.height + gap)
        }
    }
}

private val CaptionGap = 8.dp

/**
 * A detail page's actions: [primary] first, then the icon actions. Beside a poster the
 * row flows onto a second line when it must; on a phone [primary] takes whatever width
 * the icons leave, and is handed a fill modifier to do it with.
 */
@Composable
internal fun DetailActionRow(
    wide: Boolean,
    modifier: Modifier = Modifier,
    exitDown: FocusRequester? = null,
    primary: (@Composable (fill: Modifier) -> Unit)? = null,
    secondary: @Composable () -> Unit,
) {
    // Left has nothing of the row's own to reach, and geometry found the cast or the foot of
    // the page instead. Cancelling hands the press to the scaffold, which opens the rail.
    // Redirected on exit rather than per button, so a wrapped second line still steps through.
    val exits = Modifier.focusProperties {
        onExit = {
            when (requestedFocusDirection) {
                FocusDirection.Down -> exitDown?.requestFocus()
                FocusDirection.Left -> cancelFocusChange()
                else -> Unit
            }
        }
    }
    val group = modifier.fillMaxWidth().focusKeyGroup("actions").then(exits).focusGroup()
    if (wide) {
        FlowRow(
            modifier = group,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            primary?.invoke(Modifier)
            secondary()
        }
    } else {
        Row(
            modifier = group,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (primary != null) Box(Modifier.weight(1f)) { primary(Modifier.fillMaxWidth()) }
            secondary()
        }
    }
}
