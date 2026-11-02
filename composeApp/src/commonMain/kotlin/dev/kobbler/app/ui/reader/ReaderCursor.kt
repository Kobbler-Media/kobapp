package dev.kobbler.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Blocks the reading cursor can sit on: those drawn on the pages now showing, in order. */
internal fun blocksOnPages(paging: ReaderPaging): List<Int> {
    paging.view?.page?.takeIf { paging.fitted != null }?.let { return listOf(it.blockIndex) }
    val layout = paging.layout ?: return emptyList()
    return paging.shownPages.flatMap { page -> layout.pages.getOrNull(page)?.slices.orEmpty().map { it.blockIndex } }.distinct()
}

/** The neighbour of [from] in [blocks], or null when the cursor would leave the page. */
internal fun stepCursor(blocks: List<Int>, from: Int, forward: Boolean): Int? {
    val at = blocks.indexOf(from)
    if (at < 0) return blocks.firstOrNull()
    return blocks.getOrNull(if (forward) at + 1 else at - 1)
}

/**
 * The D-pad reading cursor: a block on the current page that Center starts reading
 * aloud from. Stepping off either end of the page turns it and lands on the near edge of
 * the new one, so the cursor walks the book block by block.
 */
@Stable
internal class PageCursor(private val paging: ReaderPaging, private val enabled: Boolean) {
    var block by mutableStateOf<Int?>(null)
    private var landOnLast = false

    fun move(forward: Boolean, onTurn: (Int) -> Unit): Boolean {
        if (!enabled) return false
        val from = block ?: return false
        stepCursor(blocksOnPages(paging), from, forward)?.let {
            block = it
            return true
        }
        landOnLast = !forward
        onTurn(if (forward) 1 else -1)
        return true
    }

    fun select(start: (Int) -> Unit): Boolean {
        if (!enabled) return false
        block?.let {
            start(it)
            return true
        }
        block = blocksOnPages(paging).firstOrNull() ?: return false
        return true
    }

    fun followPage() {
        val current = block ?: return
        val blocks = blocksOnPages(paging)
        if (current !in blocks) block = if (landOnLast) blocks.lastOrNull() else blocks.firstOrNull()
        landOnLast = false
    }
}

@Composable
internal fun rememberPageCursor(paging: ReaderPaging, enabled: Boolean): PageCursor =
    remember(paging, enabled) { PageCursor(paging, enabled) }
