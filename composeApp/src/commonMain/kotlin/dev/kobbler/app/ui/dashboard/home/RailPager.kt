package dev.kobbler.app.ui.dashboard.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import kotlinx.coroutines.launch

/**
 * Hover-revealed page arrows over a horizontal rail. Pointer-only: the arrows are kept out
 * of the focus graph so keyboard/D-pad traversal still walks the cards themselves.
 */
@Composable
fun RailPager(
    state: LazyListState,
    gutter: Dp,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (!enabled) return content()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val scope = rememberCoroutineScope()
    val page = { direction: Int ->
        scope.launch {
            val info = state.layoutInfo
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            state.animateScrollBy(direction * (viewport - 2 * info.beforeContentPadding).toFloat())
        }
    }
    Box(Modifier.hoverable(hover)) {
        content()
        PagerArrow(hovered && state.canScrollBackward, Icons.Outlined.ChevronLeft, "Previous", Alignment.CenterStart, gutter) { page(-1) }
        PagerArrow(hovered && state.canScrollForward, Icons.Outlined.ChevronRight, "Next", Alignment.CenterEnd, gutter) { page(1) }
    }
}

@Composable
private fun BoxScope.PagerArrow(
    visible: Boolean,
    icon: ImageVector,
    label: String,
    alignment: Alignment,
    gutter: Dp,
    onClick: () -> Unit,
) {
    val colors = KobappTheme.colors
    AnimatedVisibility(visible, Modifier.align(alignment), enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .padding(horizontal = (gutter - 20.dp).coerceAtLeast(4.dp))
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.elev2.copy(alpha = 0.92f))
                .border(1.dp, colors.lineSoft, CircleShape)
                .focusProperties { canFocus = false }
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = colors.textHi, modifier = Modifier.size(22.dp))
        }
    }
}
