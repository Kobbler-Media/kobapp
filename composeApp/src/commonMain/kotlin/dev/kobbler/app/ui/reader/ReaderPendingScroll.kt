package dev.kobbler.app.ui.reader

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val CONTENT_WAIT_MS = 2000L

/** Where a chapter change wants to land once the new chapter has been measured. */
internal sealed interface PendingScroll {
    data object Bottom : PendingScroll
    data class Fraction(val value: Float) : PendingScroll
}

/** Scrollable extent of [scrollState] once the freshly composed chapter has laid out.
 *  Returns whatever the extent is at the timeout rather than hanging on an empty chapter. */
internal suspend fun awaitScrollExtent(scrollState: ScrollState): Int {
    withTimeoutOrNull(CONTENT_WAIT_MS) {
        snapshotFlow { scrollState.maxValue }.first { it > 0 }
        withFrameNanos { }
    }
    return scrollState.maxValue
}
