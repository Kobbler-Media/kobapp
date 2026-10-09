package dev.kobbler.app.ui.reader

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.MutableState
import androidx.compose.ui.graphics.Color

/**
 * Everything the reading cursor needs to move and paint.
 *
 * Holds the [ScrollState] rather than a scroll offset so the position is read in the draw
 * and event phases: reading it during composition would recompose the whole eagerly-built
 * chapter body on every frame of a page-turn animation.
 */
internal class ReaderCursorConfig(
    val bounds: Map<Int, IntRange>,
    val scrollState: ScrollState,
    val viewportHeight: Int,
    val bandColor: Color,
    val onScrollTo: (Int) -> Unit,
    val onStartReadingAt: (Int) -> Unit,
)

/** Nothing here free-scrolls: the cursor lands on a [scrollToShow] offset, which stays on
 *  the page grid that every later page turn is measured against. */
internal fun ReaderCursorConfig.moveCursor(block: MutableState<Int?>, forward: Boolean): Boolean {
    val from = block.value ?: return false
    val next = stepCursor(bounds, from, forward) ?: return false
    block.value = next
    scrollToShow(bounds, next, scrollState.value, viewportHeight, scrollState.maxValue)?.let(onScrollTo)
    return true
}

internal fun ReaderCursorConfig.selectCursor(block: MutableState<Int?>): Boolean {
    val active = block.value
    if (active != null) {
        onStartReadingAt(active)
        return true
    }
    block.value = blockAtPageTop(bounds, scrollState.value) ?: return false
    return true
}

internal fun ReaderCursorConfig.followPageTurn(block: MutableState<Int?>, landing: Int?) {
    if (block.value == null) return
    block.value = landing?.let { blockAtPageTop(bounds, it) }
}
