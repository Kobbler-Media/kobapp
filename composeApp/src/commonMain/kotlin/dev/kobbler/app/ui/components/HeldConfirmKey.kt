package dev.kobbler.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

private val ConfirmKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar)

private fun KeyEvent.isConfirm() = key in ConfirmKeys

/**
 * Whether OK is physically down. Process-wide because the press that opens a long-press
 * menu starts in the page's window and is released in the menu's own.
 */
internal object ConfirmKeyHold {
    var held = false
        private set

    fun track(event: KeyEvent) {
        if (event.isConfirm()) held = event.type == KeyEventType.KeyDown
    }
}

internal fun Modifier.trackConfirmHold(): Modifier = onPreviewKeyEvent { ConfirmKeyHold.track(it); false }

/**
 * For a window that opened while OK was still held. A remote keeps repeating the key-down
 * until it is let go, and a clickable that sees a down and then the up clicks — so the
 * focused entry would fire on the release of the very press that opened it.
 */
internal class HeldConfirmSwallower(private var swallowing: Boolean) {
    fun onPreviewKey(event: KeyEvent): Boolean {
        ConfirmKeyHold.track(event)
        if (!swallowing || !event.isConfirm()) return false
        if (event.type == KeyEventType.KeyUp) swallowing = false
        return true
    }
}
