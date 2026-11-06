package dev.kobbler.app.ui.reader.layout

import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.FloatSide
import dev.kobbler.app.ui.reader.pack.Frame

internal class Paginator(
    private val blocks: List<Block>,
    private val measurer: BlockMeasurer,
    private val pageHeight: Float,
    private val checkActive: () -> Unit,
    private val start: PageStart = PageStart(),
    private val pageLimit: Int = Int.MAX_VALUE,
    private val onPage: (List<PageLayout>) -> Unit = {},
) {
    private val measured = arrayOfNulls<MeasuredBlock>(blocks.size)
    private val boxes by lazy { BoxSpans(blocks, measurer) }
    private val pages = mutableListOf<PageLayout>()
    private val slices = mutableListOf<PageSlice>()
    private var y = 0f
    private var resumePart = start.part
    private var float: FloatArea? = null

    fun run(): List<PageLayout> {
        var i = start.block
        while (i < blocks.size && pages.size < pageLimit) {
            checkActive()
            i = place(i)
        }
        if (pages.size < pageLimit) flush()
        if (pages.isEmpty()) pages += PageLayout(start.page, emptyList(), emptyList())
        return pages.take(pageLimit)
    }

    private fun firstPart(i: Int): Int =
        if (i == start.block) resumePart.also { resumePart = 0 } else 0

    private fun measuredAt(i: Int): MeasuredBlock =
        measured[i] ?: measurer.measure(blocks[i]).also { measured[i] = it }

    private fun place(i: Int): Int {
        val block = blocks[i]
        if (block.breakBefore) flush()
        val m = measuredAt(i)
        settleFloat(block, m)
        return when (m) {
            is MeasuredBlock.Text -> placeText(i, m)
            is MeasuredBlock.Table -> placeTable(i, m)
            is MeasuredBlock.Image -> m.float?.let { placeFloat(i, m, it.side) }
                ?: placeWhole(i, m.height) { top -> ImageSlice(i, m.image, m.left, top, m.width, m.height) }
            is MeasuredBlock.Rule -> placeWhole(i, m.height) { top -> RuleSlice(i, m.left, top, m.width, m.height, m.style) }
            is MeasuredBlock.Fixed -> {
                if (slices.isNotEmpty()) newPageFor(i).let { if (it != i) return it }
                slices += FixedSlice(i, m.fixed, m.left, m.top, m.width, m.height)
                flush()
                i + 1
            }
            MeasuredBlock.Empty -> i + 1
        }
    }

    /** Only text runs beside a float, and one float at a time: anything else goes below it. */
    private fun settleFloat(block: Block, m: MeasuredBlock) {
        val f = float ?: return
        val clears = when (m) {
            is MeasuredBlock.Text -> f.clearedBy(block.clear)
            MeasuredBlock.Empty -> false
            else -> true
        }
        if (clears) {
            y = maxOf(y, f.bottom)
            float = null
        }
    }

    /** A float leaves [y] where it was, for the text after it to start level with it. */
    private fun placeFloat(i: Int, m: MeasuredBlock.Image, side: FloatSide): Int {
        if (slices.isNotEmpty() && y + gapFor(i) + m.height > pageHeight) {
            newPageFor(i).let { if (it != i) return it }
        }
        val slice = ImageSlice(i, m.image, m.left, y + gapFor(i), m.width, m.height, floating = true)
        slices += slice
        float = FloatArea(slice, side, m.gap)
        return i + 1
    }

    private fun gapFor(i: Int): Float =
        if (slices.all { it === float?.slice }) boxes.openingSpace(i) else measurer.spaceBefore(blocks[i])

    /** Lines of [m] from [from] on; while [until] is set, only those starting above it. */
    private class Run(val m: MeasuredBlock.Text, val from: Int, val opens: Boolean, val until: Float? = null)

    private fun openingRun(i: Int, m: MeasuredBlock.Text): Run {
        val part = firstPart(i)
        if (part == TailPart) return Run(measurer.textFrom(blocks[i], start.offset), 0, opens = false)
        if (part > 0) return Run(m, part, opens = false)
        val f = float ?: return Run(m, 0, opens = true)
        if (y + gapFor(i) >= f.bottom) {
            float = null
            return Run(m, 0, opens = true)
        }
        val beside = measurer.textBeside(blocks[i], f)
        if (beside == null) {
            y = maxOf(y, f.bottom)
            float = null
            return Run(m, 0, opens = true)
        }
        return Run(beside, 0, opens = true, until = f.bottom)
    }

    private inline fun placeWhole(i: Int, height: Float, slice: (top: Float) -> PageSlice): Int {
        if (slices.isNotEmpty() && y + gapFor(i) + height > pageHeight) {
            newPageFor(i).let { if (it != i) return it }
        }
        val top = y + gapFor(i)
        slices += slice(top)
        y = top + height
        return i + 1
    }

    /**
     * A run beside a float ends at the first line below it, and the rest of the block is
     * laid out again at full width. Cut by the page beside a float or below one, the block
     * goes on at full width from that character, which is how a page break records it.
     */
    private fun placeText(i: Int, m: MeasuredBlock.Text): Int {
        val block = blocks[i]
        val style = measurer.styleOf(block)
        val orphans = (style.orphans ?: DefaultOrphans).coerceAtLeast(1)
        val widows = (style.widows ?: DefaultWidows).coerceAtLeast(1)
        var run = openingRun(i, m)
        while (true) {
            val layout = run.m.layout
            val lineCount = layout.lineCount
            val from = run.from
            val gap = if (run.opens && from == 0) gapFor(i) else 0f
            val fromTop = layout.getLineTop(from)
            val avail = pageHeight - y - gap
            var end = lineCount
            run.until?.let { bottom ->
                end = from + 1
                while (end < lineCount && y + gap + layout.getLineTop(end) - fromTop < bottom) end++
            }
            var fit = 0
            while (from + fit < end && layout.getLineBottom(from + fit) - fromTop <= avail) fit++
            val remaining = end - from
            val ends = end == lineCount
            val take = when {
                fit >= remaining -> remaining
                slices.isEmpty() -> {
                    val t = fit.coerceAtLeast(1)
                    if (ends && remaining - t < widows) (remaining - widows).coerceAtLeast(1).coerceAtMost(t) else t
                }
                run.opens && block.avoidBreakInside && layout.size.height <= pageHeight -> 0
                else -> {
                    var t = fit
                    if (ends && remaining - t < widows) t = remaining - widows
                    if (t < orphans && run.opens) 0 else t
                }
            }
            if (take == 0) {
                newPageFor(i).let { if (it != i) return it }
                if (run.until != null) run = Run(m, 0, opens = true)
                continue
            }
            val last = from + take - 1
            val top = y + gap
            val marker = run.m.marker?.takeIf { run.opens && from == 0 }?.let {
                PlacedMarker(it, run.m.markerLeft, top + layout.firstBaseline - it.firstBaseline)
            }
            val slice = TextSlice(i, layout, from, last, run.m.left, top, marker, run.m.base)
            slices += slice
            y = top + slice.height
            val next = last + 1
            if (next == lineCount) return i + 1
            val carried = run.m.base + layout.getLineStart(next)
            run = when {
                next == end -> {
                    float = null
                    Run(measurer.textFrom(block, carried), 0, opens = false)
                }
                run.until == null && run.m.base == 0 -> {
                    flush()
                    Run(run.m, next, opens = false)
                }
                else -> {
                    flush()
                    Run(measurer.textFrom(block, carried), 0, opens = false)
                }
            }
        }
    }

    private fun placeTable(i: Int, m: MeasuredBlock.Table): Int {
        val rows = m.table.rows
        if (rows.isEmpty()) return i + 1
        val block = blocks[i]
        if (block.avoidBreakInside && slices.isNotEmpty() && m.table.totalHeight <= pageHeight &&
            y + gapFor(i) + m.table.totalHeight > pageHeight
        ) {
            newPageFor(i).let { if (it != i) return it }
        }
        var from = firstPart(i)
        while (from < rows.size) {
            val gap = if (from == 0) gapFor(i) else 0f
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
            val floats = slice is ImageSlice && slice.floating
            if (!(blocks[slice.blockIndex].keepWithNext || floats) || !slice.isWholeBlock()) break
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
        float = null
        if (slices.isEmpty()) return
        pages += PageLayout(start.page + pages.size, slices.toList(), frameBands(), boxes.bands(slices, pageHeight))
        slices.clear()
        y = 0f
        onPage(pages)
    }

    private fun frameBands(): List<FrameBand> {
        val bands = mutableListOf<FrameBand>()
        var current: FrameBand? = null
        var currentInset = 0f
        for (slice in slices) {
            val block = blocks[slice.blockIndex]
            val frame = block.frame?.takeIf { it.drawn(block) }
            if (frame == null) {
                current?.let(bands::add)
                current = null
                continue
            }
            val gutter = measurer.basePx * FrameGutterEm
            val left = (measurer.insetLeft(block) - gutter).coerceAtLeast(0f)
            val open = current
            when {
                // A quote's paragraphs keep their own indents (an attribution set further in),
                // but the bar belongs to the quote as a whole.
                open != null && open.frame == frame && frame == Frame.Quote ->
                    current = open.copy(left = minOf(open.left, left), bottom = slice.bottom)
                open != null && open.frame == frame && currentInset == block.insetStart ->
                    current = open.copy(bottom = slice.bottom)
                else -> {
                    open?.let(bands::add)
                    current = FrameBand(
                        frame = frame,
                        left = left,
                        right = measurer.insetRight(block) + if (frame == Frame.Aside) gutter else 0f,
                        top = slice.top,
                        bottom = slice.bottom,
                    )
                    currentInset = block.insetStart
                }
            }
        }
        current?.let(bands::add)
        return bands
    }

    /** The quote bar and aside panel are the app's own marks, so they give way to the book's own styling. */
    private fun Frame.drawn(block: Block): Boolean = when (this) {
        Frame.Figure -> false
        Frame.Quote -> !measurer.book.decorations || block.framePlain
        Frame.Aside -> !measurer.book.decorations || block.containers.isEmpty()
    }

    private companion object {
        const val DefaultOrphans = 2
        const val DefaultWidows = 2
        const val FrameGutterEm = 0.75f
    }
}
