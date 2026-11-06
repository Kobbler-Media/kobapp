package dev.kobbler.app.ui.reader.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import dev.kobbler.app.ui.reader.pack.BlockOffset

@Immutable
data class PageRect(val page: Int, val rect: Rect)

/**
 * A chapter's pages for one page size. Until [complete], only some pages are laid out and
 * the others are empty; until [paginated], the pages past the end of [pages] are not known yet.
 */
@Immutable
class ChapterLayout(
    val pages: List<PageLayout>,
    val pageSize: IntSize,
    val breaks: PageBreaks = PageBreaks.of(pages),
    val complete: Boolean = true,
    val paginated: Boolean = complete,
) {
    fun pageOf(block: Int, offset: Int): Int = breaks.pageOf(block, offset)

    fun positionAtPageStart(page: Int): BlockOffset = breaks.startOf(page)

    /** Text beside a float shares its rows with the float, so the column decides between them. */
    private fun sliceAt(page: Int, x: Float, y: Float): PageSlice? {
        val row = pages.getOrNull(page)?.slices?.filter { y >= it.top && y < it.bottom }.orEmpty()
        return row.firstOrNull { x >= it.left && x < it.left + it.width }
            ?: row.minByOrNull { if (x < it.left) it.left - x else x - (it.left + it.width) }
    }

    fun hitTest(page: Int, x: Float, y: Float): BlockOffset? {
        val slice = sliceAt(page, x, y) ?: return null
        if (slice !is TextSlice) return BlockOffset(slice.blockIndex, 0)
        val offset = slice.base + slice.layout.getOffsetForPosition(slice.toLayout(x, y))
        return BlockOffset(slice.blockIndex, offset.coerceIn(slice.startOffset, slice.endOffset))
    }

    fun charAt(page: Int, x: Float, y: Float): BlockOffset? {
        val slice = sliceAt(page, x, y) ?: return null
        val hit = hitTest(page, x, y) ?: return null
        if (slice !is TextSlice) return hit
        val point = slice.toLayout(x, y)
        val length = slice.layout.layoutInput.text.length
        return listOf(hit.offset, hit.offset - 1)
            .firstOrNull { it - slice.base in 0 until length && slice.layout.getBoundingBox(it - slice.base).contains(point) }
            ?.let { BlockOffset(hit.block, it) }
    }

    fun lineBoundsOf(block: Int, offset: Int): PageRect? {
        val page = pageOf(block, offset)
        val slices = pages.getOrNull(page)?.slices?.filter { it.blockIndex == block }.orEmpty()
        val slice = slices.lastOrNull { it.startOffset() <= offset } ?: slices.firstOrNull() ?: return null
        if (slice !is TextSlice) return PageRect(page, Rect(slice.left, slice.top, slice.left + slice.width, slice.bottom))
        val line = slice.layout.getLineForOffset((offset - slice.base).coerceAtLeast(0)).coerceIn(slice.firstLine, slice.lastLine)
        val dy = slice.top - slice.layoutTop
        return PageRect(
            page,
            Rect(
                slice.left,
                slice.layout.getLineTop(line) + dy,
                slice.left + slice.width,
                slice.layout.getLineBottom(line) + dy,
            ),
        )
    }
}

internal fun PageSlice.startOffset(): Int = if (this is TextSlice) startOffset else 0

internal fun TextSlice.toLayout(x: Float, y: Float): Offset = Offset(x - left, y - top + layoutTop)
