package dev.kobbler.app.ui.dashboard

/**
 * What a `GridCells.FixedSize` grid leaves unused at its end, in pixels as the grid itself
 * measures. It lays out whole columns from the start, and a full-span item is only as
 * wide as those columns, so a rail bleeding to the screen edge has to cover this as well
 * as the content padding.
 */
internal fun fixedGridSlackPx(viewport: Int, start: Int, end: Int, cell: Int, gap: Int): Int {
    val available = viewport - start - end
    if (available < cell) return 0
    val columns = (available + gap) / (cell + gap)
    return available - (cell * columns + gap * (columns - 1))
}
