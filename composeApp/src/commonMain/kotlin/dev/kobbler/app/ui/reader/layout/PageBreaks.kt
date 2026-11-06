package dev.kobbler.app.ui.reader.layout

import androidx.compose.runtime.Immutable
import dev.kobbler.app.ui.reader.pack.BlockOffset

/**
 * Where each page of a chapter starts: the block, the character it starts at, and the
 * line (or table row) of that block the page resumes from, or [TailPart] when the page
 * lays the block's text out afresh from that character. A page always starts on an
 * empty page at the top, so these alone are enough to lay any one page out again.
 */
@Immutable
class PageBreaks(private val starts: IntArray) {
    init {
        require(starts.size % Fields == 0)
    }

    val size: Int get() = starts.size / Fields

    fun block(page: Int): Int = starts[page * Fields]

    fun offset(page: Int): Int = starts[page * Fields + 1]

    fun part(page: Int): Int = starts[page * Fields + 2]

    internal fun resumeAt(page: Int): PageStart = PageStart(block(page), part(page), page, offset(page))

    fun startOf(page: Int): BlockOffset =
        if (page in 0 until size) BlockOffset(block(page), offset(page)) else BlockOffset(0, 0)

    fun pageOf(block: Int, offset: Int): Int {
        var lo = 0
        var hi = size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val b = block(mid)
            if (b < block || (b == block && offset(mid) <= offset)) {
                found = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (found < 0) return 0
        if (block(found) != block || offset(found) != offset) return found
        while (found > 0 && block(found - 1) == block && offset(found - 1) == offset) found--
        // Only text has offsets inside a block: a table carried over from the page before starts there.
        if (found > 0 && offset == 0 && part(found) > 0) found--
        return found
    }

    fun toIntArray(): IntArray = starts.copyOf()

    override fun equals(other: Any?): Boolean = other is PageBreaks && starts.contentEquals(other.starts)

    override fun hashCode(): Int = starts.contentHashCode()

    override fun toString(): String = (0 until size).joinToString(prefix = "PageBreaks(", postfix = ")") {
        "${block(it)}:${offset(it)}/${part(it)}"
    }

    companion object {
        private const val Fields = 3

        fun of(pages: List<PageLayout>): PageBreaks {
            val starts = IntArray(pages.size * Fields)
            pages.forEachIndexed { i, page ->
                val first = page.slices.firstOrNull()
                starts[i * Fields] = first?.blockIndex ?: 0
                starts[i * Fields + 1] = first?.startOffset() ?: 0
                starts[i * Fields + 2] = first?.part() ?: 0
            }
            return PageBreaks(starts)
        }
    }
}

internal data class PageStart(val block: Int = 0, val part: Int = 0, val page: Int = 0, val offset: Int = 0)

internal const val TailPart = -1

internal fun PageSlice.part(): Int = when (this) {
    is TextSlice -> if (base > 0) TailPart else firstLine
    is TableSlice -> rows.first
    else -> 0
}
