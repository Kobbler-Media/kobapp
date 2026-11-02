package dev.kobbler.app.ui.reader.layout

import dev.kobbler.app.ui.reader.layout.LayoutRig.assertWellFormed
import dev.kobbler.app.ui.reader.layout.LayoutRig.filler
import dev.kobbler.app.ui.reader.layout.LayoutRig.image
import dev.kobbler.app.ui.reader.layout.LayoutRig.layout
import dev.kobbler.app.ui.reader.layout.LayoutRig.lineHeight
import dev.kobbler.app.ui.reader.layout.LayoutRig.lines
import dev.kobbler.app.ui.reader.layout.LayoutRig.linesOn
import dev.kobbler.app.ui.reader.layout.LayoutRig.pageHeightFor
import dev.kobbler.app.ui.reader.layout.LayoutRig.table
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockKind
import dev.kobbler.app.ui.reader.pack.FixedPageDto
import dev.kobbler.app.ui.reader.pack.ImageRef
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PaginationRulesTest {

    private data class SplitCase(
        val name: String,
        val room: Int,
        val paragraph: Int,
        val expectedOnFirstPage: Int,
        val style: PackTextStyle = PackTextStyle(),
        val avoidBreakInside: Boolean = false,
    )

    @Test
    fun `paragraphs split at line boundaries honouring widows and orphans`() {
        val cases = listOf(
            SplitCase("fits whole", room = 3, paragraph = 3, expectedOnFirstPage = 3),
            SplitCase("plain split", room = 3, paragraph = 10, expectedOnFirstPage = 3),
            SplitCase("orphan pushes paragraph", room = 1, paragraph = 10, expectedOnFirstPage = 0),
            SplitCase("widow pulls a line over", room = 3, paragraph = 4, expectedOnFirstPage = 2),
            SplitCase("too short to split", room = 2, paragraph = 3, expectedOnFirstPage = 0),
            SplitCase("book orphans", room = 2, paragraph = 10, expectedOnFirstPage = 0, style = PackTextStyle(orphans = 3)),
            SplitCase("book widows", room = 5, paragraph = 7, expectedOnFirstPage = 4, style = PackTextStyle(widows = 3)),
            SplitCase("avoid break inside", room = 3, paragraph = 5, expectedOnFirstPage = 0, avoidBreakInside = true),
        )
        for (case in cases) {
            val blocks = listOf(filler(case.room), lines(case.paragraph, style = 1).copy(avoidBreakInside = case.avoidBreakInside))
            val result = layout(blocks, styles = listOf(PackTextStyle(), case.style))
            assertWellFormed(result)
            assertEquals(case.expectedOnFirstPage, linesOn(result, 0, 1), case.name)
            val total = result.pages.indices.sumOf { linesOn(result, it, 1) }
            assertEquals(case.paragraph, total, "${case.name}: every line placed once")
        }
    }

    @Test
    fun `long paragraph flows over many pages with contiguous lines`() {
        val result = layout(listOf(lines(35)))
        assertWellFormed(result)
        val slices = result.pages.flatMap { it.slices }.map { it as TextSlice }
        assertEquals(listOf(0 to 9, 10 to 19, 20 to 29, 30 to 34), slices.map { it.firstLine to it.lastLine })
        assertTrue(slices.drop(1).all { it.top == 0f })
    }

    @Test
    fun `break_before starts a new page but never an empty one`() {
        val empty = Block(breakBefore = true)
        val cases = listOf(
            listOf(lines(1), lines(1).copy(breakBefore = true)) to 2,
            listOf(lines(1).copy(breakBefore = true), lines(1)) to 1,
            listOf(lines(1), empty, empty, lines(1)) to 2,
            listOf(lines(1), lines(1).copy(breakBefore = true), lines(1).copy(breakBefore = true)) to 3,
        )
        for ((blocks, pages) in cases) {
            val result = layout(blocks)
            assertWellFormed(result)
            assertEquals(pages, result.pages.size, blocks.joinToString { "${it.breakBefore}" })
        }
    }

    @Test
    fun `empty chapter has one page`() {
        assertEquals(1, layout(emptyList()).pages.size)
    }

    @Test
    fun `keep_with_next heading is never the last thing on a page`() {
        val heading = lines(1, kind = BlockKind.Heading).copy(keepWithNext = true)
        val result = layout(listOf(filler(2), heading, lines(10)))
        assertWellFormed(result)
        assertEquals(listOf(0), result.pages[0].slices.map { it.blockIndex })
        assertEquals(1, result.pages[1].slices.first().blockIndex)
        assertTrue(linesOn(result, 1, 2) >= 2)
    }

    @Test
    fun `chained headings move together and a lone heading still terminates`() {
        val heading = lines(1, kind = BlockKind.Heading).copy(keepWithNext = true)
        val chained = layout(listOf(filler(3), heading, heading, lines(10)))
        assertEquals(listOf(0), chained.pages[0].slices.map { it.blockIndex })
        assertEquals(listOf(1, 2, 3), chained.pages[1].slices.map { it.blockIndex }.take(3))

        val alone = layout(listOf(heading, image(10, pageHeightFor(10) - 2)))
        assertWellFormed(alone)
        assertEquals(2, alone.pages.size)
    }

    @Test
    fun `space before collapses at the top of a page`() {
        val spaced = lines(1).copy(spaceBefore = 2f)
        val result = layout(listOf(lines(1), spaced, lines(1).copy(breakBefore = true), spaced))
        val first = result.pages[0].slices
        assertEquals(first[0].bottom + 32f, first[1].top)
        val second = result.pages[1].slices
        assertEquals(0f, second[0].top)
        assertEquals(second[0].bottom + 32f, second[1].top)
        assertEquals(0f, layout(listOf(spaced)).pages[0].slices[0].top)
    }

    @Test
    fun `images scale to fit the page and are never split`() {
        val pageHeight = pageHeightFor(10)
        val cases = listOf(
            Triple(2000, 3000, 2000f / 3000f),
            Triple(4000, 1000, 4f),
            Triple(100, 50, 2f),
        )
        for ((w, h, aspect) in cases) {
            val slice = layout(listOf(image(w, h))).pages[0].slices.single() as ImageSlice
            assertTrue(slice.width <= LayoutRig.Width && slice.height <= pageHeight, "$w x $h fits")
            assertEquals(aspect, slice.width / slice.height, 0.01f)
            assertTrue(slice.width <= w, "$w x $h is not upscaled past its pixels")
        }
        val pushed = layout(listOf(lines(5), image(100, (lineHeight * 6).toInt())))
        assertEquals(listOf(listOf(0), listOf(1)), pushed.pages.map { page -> page.slices.map { it.blockIndex } })
    }

    @Test
    fun `tables split between rows`() {
        val result = layout(listOf(lines(2), table(rows = 30)))
        assertWellFormed(result)
        val rows = result.pages.flatMap { it.slices }.filterIsInstance<TableSlice>().map { it.rows }
        assertTrue(rows.size > 1)
        assertEquals(0, rows.first().first)
        assertEquals(29, rows.last().last)
        rows.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
    }

    @Test
    fun `fixed page gets a page of its own`() {
        val fixed = Block(kind = BlockKind.FixedPage, fixed = FixedPageDto(ImageRef("p.jpg"), 600f, 900f))
        val result = layout(listOf(lines(1), fixed, lines(1)))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), result.pages.map { page -> page.slices.map { it.blockIndex } })
        val slice = assertIs<FixedSlice>(result.pages[1].slices.single())
        assertEquals(600f / 900f, slice.width / slice.height, 0.01f)
    }

    @Test
    fun `list marker hangs in the start inset`() {
        val item = lines(1).copy(marker = "1.", insetStart = 2f)
        val slice = layout(listOf(item)).pages[0].slices.single() as TextSlice
        val marker = slice.marker!!
        assertEquals(32f, slice.left)
        assertTrue(marker.left + marker.layout.size.width <= slice.left)
    }

    @Test
    fun `negative indent hangs the first line into the inset`() {
        val styles = listOf(PackTextStyle(), PackTextStyle(indent = -1f))
        val block = LayoutRig.prose(60).copy(style = 1, insetStart = 2f)
        val slice = layout(listOf(block), styles = styles).pages[0].slices.first() as TextSlice
        assertEquals(16f, slice.left)
        assertTrue(slice.layout.getLineLeft(1) >= 15f)
        assertTrue(slice.layout.getLineLeft(0) < 1f)
    }
}
