package dev.kobbler.app.ui.reader

/** Scroll offset to land on for the next page, or null if the chapter is fully revealed
 *  (caller should advance to the next chapter). Clamps to maxScroll so a final partial
 *  page is reachable when contentHeight < 2 × viewportHeight. */
internal fun nextScrollTarget(currentScroll: Int, viewportHeight: Int, maxScroll: Int): Int? {
    if (viewportHeight <= 0 || maxScroll <= 0) return null
    if (currentScroll >= maxScroll) return null
    return (currentScroll + viewportHeight).coerceAtMost(maxScroll)
}

/** Scroll offset to land on for the previous page, or null if already at the top
 *  (caller should regress to the previous chapter). */
internal fun previousScrollTarget(currentScroll: Int, viewportHeight: Int): Int? {
    if (viewportHeight <= 0) return null
    if (currentScroll <= 0) return null
    return (currentScroll - viewportHeight).coerceAtLeast(0)
}

/** Bottom-bar page counter derived from actual scroll bounds, not contentHeight. */
internal fun pageCounter(currentScroll: Int, viewportHeight: Int, maxScroll: Int): Pair<Int, Int> {
    if (viewportHeight <= 0 || maxScroll <= 0) return 0 to 1
    val totalPages = (maxScroll - 1) / viewportHeight + 2
    val currentPage = if (currentScroll >= maxScroll) totalPages - 1
        else (currentScroll / viewportHeight).coerceIn(0, totalPages - 1)
    return currentPage to totalPages
}

internal class PaginatedLayoutResult(val totalHeight: Int, val blockYs: IntArray)

/** Pack a sequence of measured block heights onto fixed-height pages, returning the total
 *  height (rounded up to a page multiple) and each block's Y position.
 *
 *  Rules:
 *  - A block whose bottom would cross the current page boundary is pushed to the next page;
 *    the gap on the current page is left as whitespace so no text gets cut.
 *  - A single block taller than [pageHeight] is allowed to bleed across pages — pageStart
 *    is bumped forward so subsequent blocks still align cleanly.
 *  - When [pageHeight] is 0 (viewport not yet measured), blocks are just stacked linearly. */
internal fun computePaginatedLayout(blockHeights: IntArray, pageHeight: Int): PaginatedLayoutResult {
    if (blockHeights.isEmpty()) return PaginatedLayoutResult(0, IntArray(0))
    if (pageHeight <= 0) {
        val ys = IntArray(blockHeights.size)
        var y = 0
        for (i in blockHeights.indices) {
            ys[i] = y
            y += blockHeights[i]
        }
        return PaginatedLayoutResult(y, ys)
    }
    val ys = IntArray(blockHeights.size)
    var currentY = 0
    var pageStart = 0
    for (i in blockHeights.indices) {
        val h = blockHeights[i]
        if (currentY + h > pageStart + pageHeight && currentY > pageStart) {
            currentY = pageStart + pageHeight
            pageStart = currentY
        }
        ys[i] = currentY
        currentY += h
        while (currentY > pageStart + pageHeight) pageStart += pageHeight
    }
    val totalHeight = if (currentY == 0) 0 else ((currentY - 1) / pageHeight + 1) * pageHeight
    return PaginatedLayoutResult(totalHeight, ys)
}

/** Offset a page turn must be computed from. [nextScrollTarget] reading the live scroll
 *  measures a half-finished animation when a second press arrives mid-turn, landing short
 *  of a page and permanently skewing the grid — so the last committed page offset wins
 *  while the scroll is still converging on it. A divergence of more than a page means
 *  something other than paging moved the scroll (a touch drag), and the live offset takes
 *  over again. */
internal fun pageTurnBase(committed: Int?, liveScroll: Int, viewportHeight: Int): Int {
    if (committed == null || viewportHeight <= 0) return liveScroll
    val drift = committed - liveScroll
    return if (drift <= viewportHeight && drift >= -viewportHeight) committed else liveScroll
}
