package dev.kobbler.app.ui.navigation

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.pointerInput

fun KeyEvent.isBackKey(): Boolean =
    key == Key.Escape || key == Key.Back || (isAltPressed && key == Key.DirectionLeft)

fun Modifier.backInput(onBack: () -> Boolean): Modifier =
    onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.isBackKey() && onBack() }
        .pointerInput(onBack) {
            awaitPointerEventScope {
                while (true) {
                    // Initial pass, so a clickable under the pointer cannot swallow it first.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press && event.buttons.isBackPressed && onBack()) {
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
