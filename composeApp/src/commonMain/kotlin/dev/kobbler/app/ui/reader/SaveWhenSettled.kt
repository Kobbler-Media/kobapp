package dev.kobbler.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

private const val SettleMillis = 400L

/** Calls [save] once [value] has held still for a moment, and on leaving if it never did. */
@Composable
internal fun SaveWhenSettled(value: Any, save: () -> Unit) {
    val currentSave by rememberUpdatedState(save)
    val pending = remember { PendingSave(initial = value) }
    LaunchedEffect(value) {
        if (value == pending.saved) {
            pending.dirty = false
            return@LaunchedEffect
        }
        pending.dirty = true
        delay(SettleMillis)
        currentSave()
        pending.saved = value
        pending.dirty = false
    }
    DisposableEffect(Unit) {
        onDispose { if (pending.dirty) currentSave() }
    }
}

private class PendingSave(initial: Any) {
    var saved: Any = initial
    var dirty = false
}
