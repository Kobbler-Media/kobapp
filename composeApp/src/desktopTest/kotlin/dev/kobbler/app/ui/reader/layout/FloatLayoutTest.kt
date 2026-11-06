package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.kobbler.app.ui.reader.layout.LayoutRig.layout
import dev.kobbler.app.ui.reader.layout.LayoutRig.lineHeight
import dev.kobbler.app.ui.reader.layout.LayoutRig.lines
import dev.kobbler.app.ui.reader.layout.LayoutRig.pageHeightFor
import dev.kobbler.app.ui.reader.layout.LayoutRig.prose
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockFloat
import dev.kobbler.app.ui.reader.pack.BlockKind
import dev.kobbler.app.ui.reader.pack.BlockOffset
import dev.kobbler.app.ui.reader.pack.Clear
import dev.kobbler.app.ui.reader.pack.FloatSide
import dev.kobbler.app.ui.reader.pack.ImageRef
import dev.kobbler.app.ui.reader.pack.BorderLine
import dev.kobbler.app.ui.reader.pack.Borders
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import dev.kobbler.app.ui.reader.pack.Span
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A floated picture sits at one edge of the column and the text after it runs beside it. */
class FloatLayoutTest {

    private val width = LayoutRig.Width.toFloat()
    private val gap = 8f
    private val tall = pageHeightFor(20)

    private fun float(side: FloatSide = FloatSide.Left, share: Float = 0.4f, w: Int = 100, h: Int = 100) =
        Block(kind = BlockKind.Image, image = ImageRef("f.png", "Float", w, h, columnWidth = share), float = BlockFloat(side))

    private fun PageLayout.picture(): ImageSlice = slices.filterIsInstance<ImageSlice>().first()

    private fun PageLayout.texts(block: Int): List<TextSlice> = slices.filterIsInstance<TextSlice>().filter { it.blockIndex == block }

    private fun assertCovers(layout: ChapterLayout, blocks: List<Block>) {
        for ((i, block) in blocks.withIndex()) {
            if (block.kind == BlockKind.Image) continue
            val runs = layout.pages.flatMap { it.texts(i) }
            assertEquals(0, runs.first().startOffset, "block $i starts")
            runs.zipWithNext().forEach { (a, b) -> assertEquals(a.endOffset, b.startOffset, "block $i runs on") }
            assertEquals(block.text.length, runs.last().endOffset, "block $i ends")
        }
        for (page in layout.pages) for (slice in page.slices) {
            assertEquals(page.index, layout.pageOf(slice.blockIndex, slice.startOffset()), "a position on page ${page.index} maps back to it")
        }
    }

    @Test
    fun `text runs narrowed beside a float, then at full width below it`() {
        for (side in FloatSide.entries) {
            val blocks = listOf(float(side), prose(120))
            val chapter = layout(blocks, height = tall)
            val page = chapter.pages[0]
            val picture = page.picture()
            assertEquals(160f, picture.width)
            assertEquals(if (side == FloatSide.Left) 0f else width - 160f, picture.left)
            val (beside, below) = page.texts(1)
            assertEquals(if (side == FloatSide.Left) 160f + gap else 0f, beside.left, "$side")
            assertTrue(beside.width <= width - 160f - gap, "$side: ${beside.width}")
            assertTrue(beside.top < picture.bottom && beside.bottom >= picture.bottom, "$side: narrow until the picture ends")
            assertEquals(0f, below.left)
            assertEquals(width, below.width.toFloat())
            assertEquals(beside.bottom, below.top)
            assertCovers(chapter, blocks)
        }
    }

    @Test
    fun `short paragraphs one after another all run beside the float`() {
        val blocks = listOf(float(), lines(2), lines(1), prose(120))
        val page = layout(blocks, height = tall).pages[0]
        for (i in 1..3) assertEquals(160f + gap, page.texts(i).first().left, "block $i")
        assertEquals(0f, page.texts(3).last().left)
    }

    @Test
    fun `clear and anything but text move below the float`() {
        val cases = listOf(
            lines(1).copy(clear = Clear.Left) to true,
            lines(1).copy(clear = Clear.Both) to true,
            lines(1).copy(clear = Clear.Right) to false,
            LayoutRig.image(10, 10) to true,
            LayoutRig.table(2) to true,
            float() to true,
        )
        for ((block, below) in cases) {
            val page = layout(listOf(float(), lines(1), block), height = tall).pages[0]
            val first = page.picture()
            val slice = page.slices.last { it.blockIndex == 2 }
            assertEquals(below, slice.top >= first.bottom, "${block.kind} ${block.clear}")
            if (!below) assertEquals(160f + gap, slice.left)
        }
    }

    @Test
    fun `a float too wide, on too narrow a column, or with the book's spacing off is a block`() {
        val cases = listOf(
            Triple(float(share = 0.7f), LayoutRig.Width, LayoutRig.typography),
            Triple(float(share = 0.4f), 250, LayoutRig.typography),
            Triple(float(), LayoutRig.Width, LayoutRig.typography.copy(book = BookStyle.Book.with(StyleAspect.Spacing, book = false))),
        )
        for ((f, w, typography) in cases) {
            val page = layout(listOf(f, prose(40)), width = w, height = tall, typography = typography).pages[0]
            val picture = page.picture()
            assertEquals((w - picture.width) / 2, picture.left, 0.5f, "centred at $w")
            val text = page.texts(1).single()
            assertTrue(text.top >= picture.bottom, "text below at $w")
            assertEquals(0f, text.left)
        }
    }

    @Test
    fun `a float that does not fit the rest of the page moves on with its text`() {
        val blocks = listOf(lines(8), float(), prose(60))
        val chapter = layout(blocks)
        assertEquals(listOf(0), chapter.pages[0].slices.map { it.blockIndex })
        val next = chapter.pages[1]
        assertEquals(0f, next.picture().top)
        assertEquals(160f + gap, next.texts(2).first().left)
        assertCovers(chapter, blocks)
    }

    @Test
    fun `text cut by the page beside a float goes on at full width, and windows lay its pages out again`() {
        val blocks = listOf(lines(7), float(w = 100, h = 44), prose(80).copy(spaceBefore = 0.5f), prose(200, seed = 3))
        val chapter = layout(blocks)
        val first = chapter.pages[0]
        val beside = first.texts(2).single()
        assertEquals(160f + gap, beside.left)
        assertTrue(beside.lastLine < beside.layout.lineCount - 1, "the page cuts it while still beside the float")
        val carried = chapter.pages[1].slices.first() as TextSlice
        assertEquals(2, carried.blockIndex)
        assertEquals(beside.endOffset, carried.startOffset)
        assertEquals(0f, carried.left)
        assertEquals(width, carried.width.toFloat())
        assertCovers(chapter, blocks)
        for (p in chapter.pages.indices) {
            val window = runBlocking {
                layoutWindow(
                    blocks, listOf(PackTextStyle()), LayoutRig.typography, IntSize(LayoutRig.Width, pageHeightFor(10)),
                    LayoutRig.density, LayoutRig.resolver, LayoutDirection.Ltr, chapter.breaks, p..p,
                )
            }
            val page = assertNotNull(window, "window $p was refused").pages[p]
            assertEquals(chapter.pages[p].slices.map { listOf(it.blockIndex, it.startOffset(), it.left.toInt(), it.top.toInt()) },
                page.slices.map { listOf(it.blockIndex, it.startOffset(), it.left.toInt(), it.top.toInt()) }, "page $p")
        }
    }

    @Test
    fun `sides are the page's own in right-to-left text too`() {
        val chapter = runBlocking {
            layoutChapter(
                listOf(float(), prose(120)), listOf(PackTextStyle()), LayoutRig.typography, IntSize(LayoutRig.Width, tall),
                LayoutRig.density, LayoutRig.resolver, LayoutDirection.Rtl,
            )
        }
        val page = chapter.pages[0]
        assertEquals(0f, page.picture().left)
        assertEquals(160f + gap, page.texts(1).first().left)
    }

    @Test
    fun `narrowed lines are justified to the narrowed column`() {
        val justified = LayoutRig.typography.copy(justify = true)
        val beside = layout(listOf(float(), prose(120)), height = tall, typography = justified).pages[0].texts(1).first()
        assertEquals(beside.width.toFloat(), beside.layout.getLineRight(0), 1f)
    }

    @Test
    fun `taps, characters and line bounds find the right run`() {
        val chapter = layout(listOf(float(), prose(120)), height = tall)
        val page = chapter.pages[0]
        val picture = page.picture()
        val (beside, below) = page.texts(1)
        assertEquals(BlockOffset(0, 0), chapter.hitTest(0, picture.left + 10, picture.top + 10))
        val besideHit = assertNotNull(chapter.hitTest(0, beside.left + 5, beside.top + lineHeight / 2))
        assertEquals(1, besideHit.block)
        assertTrue(besideHit.offset in beside.startOffset..beside.startOffset + 2, "$besideHit")
        val belowHit = assertNotNull(chapter.hitTest(0, 5f, below.top + lineHeight / 2))
        assertTrue(belowHit.offset in below.startOffset..below.startOffset + 2, "$belowHit")
        val char = assertNotNull(chapter.charAt(0, 5f, below.top + lineHeight / 2))
        assertTrue(char.offset in below.startOffset..below.startOffset + 2, "$char")
        val bounds = assertNotNull(chapter.lineBoundsOf(1, below.startOffset + 1))
        assertEquals(below.top, bounds.rect.top, 0.5f)
        assertEquals(0f, bounds.rect.left)
    }

    @Test
    fun `highlights and span borders land on the run below the float`() {
        val styles = listOf(PackTextStyle(), PackTextStyle(borders = Borders(bottom = BorderLine(width = 0.25f, color = "#000000"))))
        val words = prose(70).text
        val blocks = listOf(float(), Block(spans = listOf(Span(words.dropLast(10)), Span(words.takeLast(10), 1))))
        val chapter = layout(blocks, height = tall, styles = styles)
        val below = chapter.pages[0].texts(1).last()
        assertEquals(blocks[1].text.length, below.endOffset, "the paragraph ends on the page")
        val bitmap = ImageBitmap(LayoutRig.Width, tall)
        CanvasDrawScope().draw(LayoutRig.density, LayoutDirection.Ltr, Canvas(bitmap), Size(width, tall.toFloat())) {
            drawTextSlice(below, listOf(TextHighlight(1, below.startOffset until below.endOffset, Color.Red)))
            drawSpanBorders(below, blocks[1], styles, LayoutRig.typography)
        }
        val px = bitmap.toPixelMap()
        assertEquals(Color.Red, px[1, below.top.toInt() + 1], "the highlight starts the run")
        val last = below.layout.lineCount - 1
        val underline = (below.top - below.layoutTop + below.layout.getLineBottom(last)).toInt() - 2
        val x = below.layout.getLineRight(last).toInt() - 3
        assertEquals(1f, px[x, underline].alpha, 0.01f, "the bordered span ends the run")
    }
}
