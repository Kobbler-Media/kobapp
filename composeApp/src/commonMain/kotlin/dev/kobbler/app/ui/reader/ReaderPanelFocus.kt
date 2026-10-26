package dev.kobbler.app.ui.reader

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import dev.kobbler.kobfocus.focusKeyGroup

private const val EntryFocusFrames = 8

private val dpadDirections = setOf(
    FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right,
    FocusDirection.Next, FocusDirection.Previous,
)

/**
 * A panel drawn over the page keeps the D-pad until BACK closes it. The bars behind it stay
 * composed and focusable, so geometry alone walks straight out of the sheet into them.
 */
@Composable
internal fun Modifier.readerPanel(key: String): Modifier = this
    .focusKeyGroup(key)
    .focusProperties {
        onExit = { if (requestedFocusDirection in dpadDirections) cancelFocusChange() }
    }
    .focusGroup()

/** A remote has no pointer to reach into a panel with, so it opens holding focus. */
@Composable
internal fun rememberPanelEntryFocus(): FocusRequester {
    val entry = remember { FocusRequester() }
    LaunchedEffect(entry) {
        repeat(EntryFocusFrames) {
            if (runCatching { entry.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            withFrameNanos {}
        }
    }
    return entry
}
