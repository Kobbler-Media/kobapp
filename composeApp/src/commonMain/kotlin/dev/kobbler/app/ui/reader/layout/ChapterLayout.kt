package dev.kobbler.app.ui.reader.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import dev.kobbler.app.ui.reader.pack.BlockOffset

@Immutable
data class PageRect(val page: Int, val rect: Rect)

@Immutable
class ChapterLayout(val pages: List<PageLayout>, val pageSize: IntSize) {
    private val starts: List<SliceStart> = pages.flatMap { page ->
        page.slices.map { SliceStart(it.blockIndex, it.startOffset(), page.index) }
    }

    fun pageOf(block: Int, offset: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val s = starts[mid]
            if (s.block < block || (s.block == block && s.offset <= offset)) {
                found = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (found < 0) return 0
        while (found > 0 && starts[found - 1].block == starts[found].block && starts[found - 1].offset == starts[found].offset) found--
        return starts[found].page
    }

    fun positionAtPageStart(page: Int): BlockOffset =
        pages.getOrNull(page)?.slices?.firstOrNull()?.let { BlockOffset(it.blockIndex, it.startOffset()) }
            ?: BlockOffset(0, 0)

    fun hitTest(page: Int, x: Float, y: Float): BlockOffset? {
        val slice = pages.getOrNull(page)?.slices?.firstOrNull { y >= it.top && y < it.bottom } ?: return null
        if (slice !is TextSlice) return BlockOffset(slice.blockIndex, 0)
        val offset = slice.layout.getOffsetForPosition(slice.toLayout(x, y))
        return BlockOffset(slice.blockIndex, offset.coerceIn(slice.startOffset, slice.endOffset))
    }

    fun charAt(page: Int, x: Float, y: Float): BlockOffset? {
        val hit = hitTest(page, x, y) ?: return null
        val slice = pages[page].slices.first { it.blockIndex == hit.block && y >= it.top && y < it.bottom }
        if (slice !is TextSlice) return hit
        val point = slice.toLayout(x, y)
        val length = slice.layout.layoutInput.text.length
        return listOf(hit.offset, hit.offset - 1)
            .firstOrNull { it in 0 until length && slice.layout.getBoundingBox(it).contains(point) }
            ?.let { BlockOffset(hit.block, it) }
    }

    fun lineBoundsOf(block: Int, offset: Int): PageRect? {
        val page = pageOf(block, offset)
        val slices = pages.getOrNull(page)?.slices?.filter { it.blockIndex == block }.orEmpty()
        val slice = slices.lastOrNull { it.startOffset() <= offset } ?: slices.firstOrNull() ?: return null
        if (slice !is TextSlice) return PageRect(page, Rect(slice.left, slice.top, slice.left + slice.width, slice.bottom))
        val line = slice.layout.getLineForOffset(offset).coerceIn(slice.firstLine, slice.lastLine)
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

    private class SliceStart(val block: Int, val offset: Int, val page: Int)
}

internal fun PageSlice.startOffset(): Int = if (this is TextSlice) startOffset else 0

internal fun TextSlice.toLayout(x: Float, y: Float): Offset = Offset(x - left, y - top + layoutTop)
