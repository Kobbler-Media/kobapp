package dev.kobbler.kobfocus

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Whether focus should be drawn, the way a browser's `:focus-visible` decides it: only
 * while the viewer is steering by keys. A click or a tap still moves focus — Compose
 * focuses whatever is pressed, and screens hand focus around on their own — but lighting
 * it up then marks a control nobody chose.
 */
@Stable
class FocusVisibility(visible: Boolean) {
    var visible by mutableStateOf(visible)
        internal set
}

/** Static because the instance never changes; reads of [FocusVisibility.visible] are tracked on their own. */
val LocalFocusVisibility = staticCompositionLocalOf { FocusVisibility(visible = true) }

/**
 * Goes on the root, where every key and every press passes on its way in. Keys arriving
 * with nothing focused never reach it, so the first of them only moves focus, unlit.
 */
fun Modifier.trackFocusVisibility(state: FocusVisibility): Modifier = this
    .onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key in SteeringKeys) state.visible = true
        false
    }
    .pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) state.visible = false
            }
        }
    }

private val SteeringKeys = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter,
    Key.Tab, Key.Enter, Key.NumPadEnter, Key.Back,
)
