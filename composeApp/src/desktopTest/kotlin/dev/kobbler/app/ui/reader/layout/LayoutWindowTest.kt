package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.kobbler.app.ui.reader.layout.LayoutRig.image
import dev.kobbler.app.ui.reader.layout.LayoutRig.lines
import dev.kobbler.app.ui.reader.layout.LayoutRig.prose
import dev.kobbler.app.ui.reader.layout.LayoutRig.table
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockOffset
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A page laid out on its own from the chapter's page breaks is the page a full layout makes. */
class LayoutWindowTest {

    private val chapter: List<Block> = buildList {
        repeat(6) { add(prose(60, seed = it)) }
        add(lines(3).copy(keepWithNext = true))
        add(table(40))
        add(image(300, 500))
        add(lines(25))
        repeat(6) { add(prose(90, seed = it + 10).copy(avoidBreakInside = it % 2 == 0)) }
        add(lines(2).copy(breakBefore = true))
    }

    private val height = LayoutRig.pageHeightFor(12)

    private fun window(blocks: List<Block>, breaks: PageBreaks, pages: IntRange, typography: ReaderTypography = LayoutRig.typography) = runBlocking {
        layoutWindow(
            blocks, listOf(PackTextStyle()), typography, IntSize(LayoutRig.Width, height),
            LayoutRig.density, LayoutRig.resolver, LayoutDirection.Ltr, breaks, pages,
        )
    }

    private fun PageLayout.shape() = slices.map { listOf(it.blockIndex, it.startOffset(), it.part(), it.top.toInt(), it.height.toInt()) }

    @Test
    fun `every window reproduces the full layout's pages`() {
        val full = LayoutRig.layout(chapter, height = height)
        assertTrue(full.pages.size > 8, "the chapter only made ${full.pages.size} pages")
        for (first in full.pages.indices) {
            val last = minOf(first + 1, full.pages.lastIndex)
            val windowed = assertNotNull(window(chapter, full.breaks, first..last), "window $first..$last was refused")
            assertFalse(windowed.complete)
            assertEquals(full.pages.size, windowed.pages.size)
            assertEquals(full.breaks, windowed.breaks)
            for (p in first..last) assertEquals(full.pages[p].shape(), windowed.pages[p].shape(), "page $p differs")
            assertTrue(windowed.pages.withIndex().filter { it.index !in first..last }.all { it.value.slices.isEmpty() })
        }
    }

    @Test
    fun `breaks over text that has since changed are refused`() {
        val full = LayoutRig.layout(chapter, height = height)
        val edited = full.breaks.block(1).let { b -> chapter.toMutableList().apply { this[b] = prose(75, seed = 99) } }
        assertNull(window(edited, full.breaks, 1..2))
    }

    @Test
    fun `breaks past the end of changed content are refused`() {
        val full = LayoutRig.layout(chapter, height = height)
        assertNull(window(chapter.take(3), full.breaks, full.pages.lastIndex - 1..full.pages.lastIndex))
    }

    @Test
    fun `the pages around a position arrive before the rest of the chapter`() {
        val blocks = List(200) { prose(80, seed = it) }
        val anchor = BlockOffset(60, 0)
        val early = mutableListOf<ChapterLayout>()
        val full = runBlocking {
            layoutChapter(
                blocks, listOf(PackTextStyle()), LayoutRig.typography, IntSize(LayoutRig.Width, height),
                LayoutRig.density, LayoutRig.resolver, early = anchor, onEarly = { early += it },
            )
        }
        val first = early.single()
        assertFalse(first.complete)
        val page = full.pageOf(anchor.block, anchor.offset)
        assertEquals(page + 2, first.pages.size, "early pages should end one past the anchor's page")
        assertEquals(page, first.pageOf(anchor.block, anchor.offset))
        for (p in first.pages.indices) assertEquals(full.pages[p].shape(), first.pages[p].shape())
        assertTrue(full.complete)
    }
}
