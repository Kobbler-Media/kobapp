package dev.kobbler.kobplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.kobbler.kobfocus.LocalFocusVisibility
import kotlinx.coroutines.delay

/**
 * Names an icon-only control while it is hovered, focused or held, in a popup over the screen
 * rather than beside the control: a label that took layout space pushed the whole row
 * along every time it came and went.
 */
@Composable
fun HoverLabel(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // A click focuses the control it lands on; without this the label would stay pinned
    // over a button the pointer has already used and moved off.
    var pressed by remember { mutableStateOf(false) }
    val focusVisible = LocalFocusVisibility.current.visible
    val wanted = enabled && (hovered || (focused && focusVisible)) && !pressed
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(wanted) {
        if (wanted) delay(HoverLabelDelayMs)
        shown = wanted
    }
    var held by remember { mutableStateOf(false) }
    var lingering by remember { mutableStateOf(false) }
    LaunchedEffect(lingering) {
        if (lingering) {
            delay(HoverLabelLingerMs)
            lingering = false
        }
    }
    Box(
        modifier
            .onFocusChanged {
                focused = it.hasFocus
                if (!it.hasFocus) pressed = false
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        when (event.type) {
                            PointerEventType.Enter -> hovered = true
                            PointerEventType.Exit -> {
                                hovered = false
                                pressed = false
                            }
                            PointerEventType.Press -> {
                                pressed = true
                                if (enabled && event.changes.any { it.type == PointerType.Touch } && awaitHold()) {
                                    held = true
                                    awaitReleaseSwallowingClick()
                                    held = false
                                    lingering = true
                                }
                            }
                        }
                    }
                }
            },
        propagateMinConstraints = true,
    ) {
        content()
        if (shown || held || lingering) {
            val density = LocalDensity.current
            Popup(popupPositionProvider = remember(density) { AboveOrBelow(with(density) { 8.dp.roundToPx() }) }, properties = PopupProperties(focusable = false)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .background(Color(0xE6202024), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

const val HoverLabelDelayMs = 350L
const val HoverLabelLingerMs = 1500L

private suspend fun AwaitPointerEventScope.awaitHold(): Boolean =
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) Unit
    } == null

// A touch screen has no hover, so a hold is the only way to ask what a button does;
// letting the release through as a click would answer by doing it.
private suspend fun AwaitPointerEventScope.awaitReleaseSwallowingClick() {
    do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

private class AboveOrBelow(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchorBounds.top - gap - popupContentSize.height
        val y = if (above >= 0) above else anchorBounds.bottom + gap
        return IntOffset(x, y)
    }
}
