package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.kobbler.app.ui.reader.layout.LayoutRig.contains
import dev.kobbler.app.ui.reader.layout.LayoutRig.layout
import dev.kobbler.app.ui.reader.layout.LayoutRig.prose
import dev.kobbler.app.ui.reader.layout.LayoutRig.table
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockKind
import dev.kobbler.app.ui.reader.pack.BlockOffset
import dev.kobbler.app.ui.reader.pack.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterLayoutLookupTest {

    private val chapter: List<Block> = buildList {
        repeat(12) { i ->
            add(prose(40 + i * 23, seed = i))
            if (i % 4 == 1) add(Block(kind = BlockKind.Heading, spans = listOf(Span("Part $i")), keepWithNext = true))
            if (i == 6) add(table(rows = 12))
            if (i == 8) add(LayoutRig.image(300, 200))
        }
    }

    @Test
    fun `position at page start maps back to the same page`() {
        val result = layout(chapter)
        assertTrue(result.pages.size > 5)
        for (page in result.pages.indices) {
            val start = result.positionAtPageStart(page)
            val first = result.pages[page].slices.first()
            // Non-text blocks only have offset 0, so a table's continuation page resolves to the table's first page.
            val expected = if (first is TableSlice && !first.rows.contains(0)) result.pages.indexOfFirst { p -> p.slices.any { it.blockIndex == first.blockIndex } } else page
            assertEquals(expected, result.pageOf(start.block, start.offset), "page $page from $start")
        }
    }

    @Test
    fun `every offset of a block resolves to a page holding it`() {
        val result = layout(chapter)
        for ((index, block) in chapter.withIndex()) {
            for (offset in 0..block.text.length step 17) {
                val page = result.pageOf(index, offset)
                assertTrue(result.pages[page].contains(BlockOffset(index, offset)), "block $index offset $offset on page $page")
            }
        }
    }

    @Test
    fun `position survives a re-layout at another size`() {
        val sizes = listOf(
            Triple(400, LayoutRig.pageHeightFor(10), LayoutRig.typography),
            Triple(300, LayoutRig.pageHeightFor(7), LayoutRig.typography),
            Triple(620, LayoutRig.pageHeightFor(18), LayoutRig.typography),
            Triple(400, LayoutRig.pageHeightFor(10), LayoutRig.typography.copy(theme = LayoutRig.typography.theme.copy(fontSize = 22.sp, lineHeight = 33.sp, fontFamily = FontFamily.Serif))),
        )
        for ((fromIndex, from) in sizes.withIndex()) {
            val before = layout(chapter, width = from.first, height = from.second, typography = from.third)
            for (page in listOf(1, before.pages.size / 2, before.pages.lastIndex)) {
                val anchor = before.positionAtPageStart(page)
                for ((toIndex, to) in sizes.withIndex()) {
                    val after = layout(chapter, width = to.first, height = to.second, typography = to.third)
                    val landed = after.pageOf(anchor.block, anchor.offset)
                    assertTrue(after.pages[landed].contains(anchor), "size $fromIndex page $page -> size $toIndex page $landed lost $anchor")
                }
            }
        }
    }

    @Test
    fun `hit test returns the character under the point`() {
        val result = layout(chapter)
        val page = 2
        val slice = result.pages[page].slices.filterIsInstance<TextSlice>().first()
        val offset = (slice.startOffset + slice.endOffset) / 2
        val box = slice.layout.getBoundingBox(offset)
        val x = slice.left + box.center.x
        val y = slice.top - slice.layoutTop + box.center.y
        assertEquals(BlockOffset(slice.blockIndex, offset), result.charAt(page, x, y))
        val caret = assertNotNull(result.hitTest(page, x, y))
        assertTrue(caret.offset == offset || caret.offset == offset + 1)
        assertNull(result.hitTest(page, x, result.pageSize.height + 10f))
    }

    @Test
    fun `line bounds of an offset sit on the page that holds it`() {
        val result = layout(chapter)
        val block = chapter.indices.first { chapter[it].text.length > 600 }
        val offset = chapter[block].text.length - 3
        val bounds = assertNotNull(result.lineBoundsOf(block, offset))
        assertEquals(result.pageOf(block, offset), bounds.page)
        val hit = assertNotNull(result.hitTest(bounds.page, bounds.rect.center.x, bounds.rect.center.y))
        assertEquals(block, hit.block)
    }
}
