package dev.kobbler.app.ui.reader.layout

import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.Frame

internal class Paginator(
    private val blocks: List<Block>,
    private val measurer: BlockMeasurer,
    private val pageHeight: Float,
    private val checkActive: () -> Unit,
) {
    private val measured = arrayOfNulls<MeasuredBlock>(blocks.size)
    private val pages = mutableListOf<PageLayout>()
    private val slices = mutableListOf<PageSlice>()
    private var y = 0f

    fun run(): List<PageLayout> {
        var i = 0
        while (i < blocks.size) {
            checkActive()
            i = place(i)
        }
        flush()
        if (pages.isEmpty()) pages += PageLayout(0, emptyList(), emptyList())
        return pages
    }

    private fun measuredAt(i: Int): MeasuredBlock =
        measured[i] ?: measurer.measure(blocks[i]).also { measured[i] = it }

    private fun place(i: Int): Int {
        val block = blocks[i]
        if (block.breakBefore) flush()
        return when (val m = measuredAt(i)) {
            is MeasuredBlock.Text -> placeText(i, m)
            is MeasuredBlock.Table -> placeTable(i, m)
            is MeasuredBlock.Image -> placeWhole(i, m.height) { top -> ImageSlice(i, m.image, m.left, top, m.width, m.height) }
            is MeasuredBlock.Rule -> placeWhole(i, m.height) { top -> RuleSlice(i, m.left, top, m.width, m.height) }
            is MeasuredBlock.Fixed -> {
                if (slices.isNotEmpty()) newPageFor(i).let { if (it != i) return it }
                slices += FixedSlice(i, m.fixed, m.left, m.top, m.width, m.height)
                flush()
                i + 1
            }
            MeasuredBlock.Empty -> i + 1
        }
    }

    private fun gapFor(block: Block): Float = if (slices.isEmpty()) 0f else block.spaceBefore * measurer.basePx

    private inline fun placeWhole(i: Int, height: Float, slice: (top: Float) -> PageSlice): Int {
        if (slices.isNotEmpty() && y + gapFor(blocks[i]) + height > pageHeight) {
            newPageFor(i).let { if (it != i) return it }
        }
        val top = y + gapFor(blocks[i])
        slices += slice(top)
        y = top + height
        return i + 1
    }

    private fun placeText(i: Int, m: MeasuredBlock.Text): Int {
        val block = blocks[i]
        val style = measurer.styleOf(block)
        val layout = m.layout
        val lineCount = layout.lineCount
        val orphans = (style.orphans ?: DefaultOrphans).coerceAtLeast(1)
        val widows = (style.widows ?: DefaultWidows).coerceAtLeast(1)
        var from = 0
        while (from < lineCount) {
            val gap = if (from == 0) gapFor(block) else 0f
            val fromTop = layout.getLineTop(from)
            val avail = pageHeight - y - gap
            var fit = 0
            while (from + fit < lineCount && layout.getLineBottom(from + fit) - fromTop <= avail) fit++
            val remaining = lineCount - from
            val take = when {
                fit >= remaining -> remaining
                slices.isEmpty() -> {
                    val t = fit.coerceAtLeast(1)
                    if (remaining - t < widows) (remaining - widows).coerceAtLeast(1).coerceAtMost(t) else t
                }
                block.avoidBreakInside && layout.size.height <= pageHeight -> 0
                else -> {
                    var t = fit
                    if (remaining - t < widows) t = remaining - widows
                    if (t < orphans) 0 else t
                }
            }
            if (take == 0) {
                newPageFor(i).let { if (it != i) return it }
                continue
            }
            val last = from + take - 1
            val top = y + gap
            val marker = m.marker?.takeIf { from == 0 }?.let {
                PlacedMarker(it, m.markerLeft, top + layout.firstBaseline - it.firstBaseline)
            }
            val slice = TextSlice(i, layout, from, last, m.left, top, marker)
            slices += slice
            y = top + slice.height
            from = last + 1
            if (from < lineCount) flush()
        }
        return i + 1
    }

    private fun placeTable(i: Int, m: MeasuredBlock.Table): Int {
        val rows = m.table.rows
        if (rows.isEmpty()) return i + 1
        val block = blocks[i]
        if (block.avoidBreakInside && slices.isNotEmpty() && m.table.totalHeight <= pageHeight &&
            y + gapFor(block) + m.table.totalHeight > pageHeight
        ) {
            newPageFor(i).let { if (it != i) return it }
        }
        var from = 0
        while (from < rows.size) {
            val gap = if (from == 0) gapFor(block) else 0f
            val fromTop = rows[from].top
            val avail = pageHeight - y - gap
            var fit = 0
            while (from + fit < rows.size && rows[from + fit].let { it.top + it.height } - fromTop <= avail) fit++
            if (fit == 0 && slices.isNotEmpty()) {
                newPageFor(i).let { if (it != i) return it }
                continue
            }
            val last = from + fit.coerceAtLeast(1) - 1
            val top = y + gap
            val slice = TableSlice(i, m.table, from..last, m.left, top)
            slices += slice
            y = top + slice.height
            from = last + 1
            if (from < rows.size) flush()
        }
        return i + 1
    }

    private fun newPageFor(i: Int): Int {
        var moved = 0
        while (moved < slices.size - 1) {
            val slice = slices[slices.size - 1 - moved]
            if (!blocks[slice.blockIndex].keepWithNext || !slice.isWholeBlock()) break
            moved++
        }
        val resumeAt = if (moved > 0) slices[slices.size - moved].blockIndex else i
        repeat(moved) { slices.removeAt(slices.lastIndex) }
        flush()
        return resumeAt
    }

    private fun PageSlice.isWholeBlock(): Boolean = when (this) {
        is TextSlice -> isWhole
        is TableSlice -> isWhole
        else -> true
    }

    private fun flush() {
        if (slices.isEmpty()) return
        pages += PageLayout(pages.size, slices.toList(), frameBands())
        slices.clear()
        y = 0f
    }

    private fun frameBands(): List<FrameBand> {
        val bands = mutableListOf<FrameBand>()
        var current: FrameBand? = null
        var currentInset = 0f
        for (slice in slices) {
            val block = blocks[slice.blockIndex]
            val frame = block.frame?.takeIf { it != Frame.Figure }
            if (frame == null) {
                current?.let(bands::add)
                current = null
                continue
            }
            val open = current
            if (open != null && open.frame == frame && currentInset == block.insetStart) {
                current = open.copy(bottom = slice.bottom)
            } else {
                open?.let(bands::add)
                val gutter = measurer.basePx * FrameGutterEm
                current = FrameBand(
                    frame = frame,
                    left = (measurer.insetLeft(block) - gutter).coerceAtLeast(0f),
                    right = measurer.insetRight(block) + if (frame == Frame.Aside) gutter else 0f,
                    top = slice.top,
                    bottom = slice.bottom,
                )
                currentInset = block.insetStart
            }
        }
        current?.let(bands::add)
        return bands
    }

    private companion object {
        const val DefaultOrphans = 2
        const val DefaultWidows = 2
        const val FrameGutterEm = 0.75f
    }
}
