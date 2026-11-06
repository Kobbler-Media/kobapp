package dev.kobbler.app.ui.reader.layout

import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.Container

/** Where each of the book's boxes begins and ends in a chapter's blocks. */
internal class BoxSpans(private val blocks: List<Block>, private val measurer: BlockMeasurer) {
    private val first = HashMap<Int, Int>()
    private val last = HashMap<Int, Int>()

    init {
        blocks.forEachIndexed { i, block ->
            for (c in block.containers) {
                first.getOrPut(c.id) { i }
                last[c.id] = i
            }
        }
    }

    private val drawn: Boolean get() = measurer.book.decorations && first.isNotEmpty()

    /** The room inside the borders of every box block [i] opens, which a page top must keep. */
    fun openingSpace(i: Int): Float {
        if (!drawn) return 0f
        return blocks[i].containers.filter { first[it.id] == i }.maxOfOrNull { it.spaceTop * measurer.basePx } ?: 0f
    }

    private fun edges(c: Container): Pair<Float, Float> {
        val left = measurer.edgeLeft(c.insetStart)
        val right = measurer.edgeRight(c.insetEnd)
        val measure = c.centred?.takeIf { measurer.book.spacing } ?: return left to right
        val width = with(measurer) { measure.px(right - left - c.inner * basePx) } + c.inner * measurer.basePx
        val half = minOf(width, right - left) / 2
        val centre = (left + right) / 2
        return centre - half to centre + half
    }

    fun bands(slices: List<PageSlice>, pageHeight: Float): List<BoxBand> {
        if (!drawn) return emptyList()
        val bands = LinkedHashMap<Int, BoxBand>()
        for (slice in slices) {
            val i = slice.blockIndex
            for (c in blocks[i].containers) {
                val opens = first[c.id] == i && slice.startsBlock
                val closes = last[c.id] == i && slice.endsBlock
                val bottom = if (closes) (slice.bottom + c.spaceBottom * measurer.basePx).coerceAtMost(pageHeight) else slice.bottom
                val (left, right) = edges(c)
                bands[c.id] = bands[c.id]?.copy(bottom = bottom, openBottom = !closes) ?: BoxBand(
                    container = c,
                    left = left,
                    right = right,
                    top = if (opens) (slice.top - c.spaceTop * measurer.basePx).coerceAtLeast(0f) else slice.top,
                    bottom = bottom,
                    openTop = !opens,
                    openBottom = !closes,
                )
            }
        }
        return bands.values.toList()
    }
}

private val PageSlice.startsBlock: Boolean
    get() = when (this) {
        is TextSlice -> startsBlock
        is TableSlice -> rows.first == 0
        else -> true
    }

private val PageSlice.endsBlock: Boolean
    get() = when (this) {
        is TextSlice -> lastLine == layout.lineCount - 1
        is TableSlice -> rows.last == table.rows.lastIndex
        else -> true
    }
