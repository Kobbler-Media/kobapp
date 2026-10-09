package dev.kobbler.app.ui.reader

/** Block the reading cursor should sit on when a page becomes current: the one under the
 *  page top, or the next one down when the top lands in pagination whitespace. */
internal fun blockAtPageTop(bounds: Map<Int, IntRange>, scroll: Int): Int? =
    blockAt(bounds, scroll)
        ?: bounds.entries.asSequence()
            .filter { it.value.first >= scroll }
            .minByOrNull { it.value.first }
            ?.key

/** Next/previous measured block, or null at the ends of the chapter. */
internal fun stepCursor(bounds: Map<Int, IntRange>, from: Int, forward: Boolean): Int? =
    if (forward) bounds.keys.filter { it > from }.minOrNull()
    else bounds.keys.filter { it < from }.maxOrNull()

/**
 * Page-grid-aligned scroll offset that brings [block] onto screen, or null when its top
 * is already visible or the block has not been measured.
 *
 * Must stay a multiple of [viewportHeight] — the transport is a plain scroll with paged
 * targets, so any offset landing off the grid permanently skews every later page turn.
 * Same rounding as the TTS follow snap in ReaderScreen so the two never disagree.
 */
internal fun scrollToShow(
    bounds: Map<Int, IntRange>,
    block: Int,
    scroll: Int,
    viewportHeight: Int,
    maxScroll: Int,
): Int? {
    if (viewportHeight <= 0) return null
    val top = bounds[block]?.first ?: return null
    if (top >= scroll && top < scroll + viewportHeight) return null
    val target = ((top / viewportHeight) * viewportHeight).coerceIn(0, maxScroll.coerceAtLeast(0))
    return if (target == scroll) null else target
}
