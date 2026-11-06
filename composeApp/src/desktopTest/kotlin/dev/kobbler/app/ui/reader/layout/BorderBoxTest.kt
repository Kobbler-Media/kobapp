package dev.kobbler.app.ui.reader.layout

import dev.kobbler.app.ui.reader.layout.LayoutRig.filler
import dev.kobbler.app.ui.reader.layout.LayoutRig.layout
import dev.kobbler.app.ui.reader.layout.LayoutRig.lines
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockKind
import dev.kobbler.app.ui.reader.pack.BorderLine
import dev.kobbler.app.ui.reader.pack.Container
import dev.kobbler.app.ui.reader.pack.Frame
import dev.kobbler.app.ui.reader.pack.ImageRef
import dev.kobbler.app.ui.reader.pack.RuleStyle
import dev.kobbler.app.ui.reader.pack.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BorderBoxTest {

    private val em = 16f
    private val solid = BorderLine(width = 0.125f)

    private fun box(id: Int) = Container(
        id = id, insetStart = 2f, insetEnd = 2f, spaceTop = 0.625f, spaceBottom = 0.625f,
        top = solid, right = solid, bottom = solid, left = solid,
    )

    private fun para(text: String, vararg boxes: Container, spaceBefore: Float = 0f) = Block(
        spans = listOf(Span(text)), containers = boxes.toList(), spaceBefore = spaceBefore,
        insetStart = if (boxes.isEmpty()) 0f else 2.625f, insetEnd = if (boxes.isEmpty()) 0f else 2.625f,
    )

    private fun without(aspect: StyleAspect) = LayoutRig.typography.copy(book = BookStyle.Book.with(aspect, book = false))
    private val noDecorations = without(StyleAspect.Decorations)

    @Test
    fun `a box runs from its first block to its last, and the next box is its own`() {
        val a = box(0)
        val b = box(1)
        val page = layout(
            listOf(
                para("Before."),
                para("One.", a, spaceBefore = 1.625f),
                para("Two.", a),
                para("Three.", b, spaceBefore = 2.25f),
                para("After.", spaceBefore = 1.625f),
            ),
        ).pages[0]
        assertEquals(listOf(a, b), page.boxes.map { it.container })
        val (first, second) = page.boxes
        val s = page.slices
        assertEquals(s[1].top - 0.625f * em, first.top)
        assertEquals(s[2].bottom + 0.625f * em, first.bottom)
        assertEquals(2 * em, first.left)
        assertEquals(LayoutRig.Width - 2 * em, first.right)
        assertTrue(first.bottom <= second.top, "the boxes overlap: $first $second")
        assertTrue(!first.openTop && !first.openBottom && !second.openTop && !second.openBottom)
    }

    @Test
    fun `a box split over a page break is open at the break`() {
        val a = box(0)
        val result = layout(listOf(filler(3), lines(6).copy(containers = listOf(a), spaceBefore = 0.625f)))
        val (before, after) = result.pages.take(2).map { page -> page.boxes.single() }
        assertTrue(!before.openTop && before.openBottom, "first page: $before")
        assertEquals(result.pages[0].slices.last().bottom, before.bottom)
        assertTrue(after.openTop && !after.openBottom, "second page: $after")
        assertEquals(0f, after.top)
        assertEquals(result.pages[1].slices.last().bottom + 0.625f * em, after.bottom)
    }

    @Test
    fun `a box opening a page keeps the room inside its border`() {
        val a = box(0)
        val opening = listOf(para("Boxed.", a, spaceBefore = 1.625f))
        val page = layout(opening).pages[0]
        assertEquals(0.625f * em, page.slices[0].top)
        assertEquals(0f, page.boxes.single().top)

        val plain = layout(opening, typography = noDecorations).pages[0]
        assertTrue(plain.boxes.isEmpty(), "drew the book's box with its styles off")
        assertEquals(0f, plain.slices[0].top)
    }

    @Test
    fun `an aside the book boxes itself gives up the app's panel`() {
        val aside = listOf(para("A sidebar.", box(0)).copy(frame = Frame.Aside))
        assertTrue(layout(aside).pages[0].frames.isEmpty())
        assertEquals(1, layout(aside, typography = noDecorations).pages[0].frames.size)
    }

    @Test
    fun `a rule the book draws keeps its own length and line`() {
        val style = RuleStyle(BorderLine(width = 0.125f), length = 0.2f)
        val styled = Block(kind = BlockKind.Rule, rule = style)
        fun rule(block: Block, publisher: Boolean = true) = assertIs<RuleSlice>(
            layout(listOf(block), typography = if (publisher) LayoutRig.typography else noDecorations).pages[0].slices.single(),
        )
        val own = rule(styled)
        assertEquals(LayoutRig.Width * 0.2f, own.width)
        assertEquals((LayoutRig.Width - own.width) / 2, own.left)
        assertEquals(style, own.style)
        val default = rule(Block(kind = BlockKind.Rule))
        assertEquals(LayoutRig.Width * 0.3f, default.width)
        assertEquals(null, default.style)
        assertEquals(default.width, rule(styled, publisher = false).width)
        assertEquals(null, rule(styled, publisher = false).style)
    }

    @Test
    fun `an image sized as a share of the column keeps that share`() {
        val image = Block(kind = BlockKind.Image, image = ImageRef("a.png", width = 1200, height = 800, columnWidth = 0.4f))
        val slice = assertIs<ImageSlice>(layout(listOf(image)).pages[0].slices.single())
        assertEquals(LayoutRig.Width * 0.4f, slice.width, 0.01f)
        assertEquals(slice.width * 800 / 1200, slice.height, 0.01f)
        val unshared = image.copy(image = image.image?.copy(columnWidth = null))
        val natural = assertIs<ImageSlice>(layout(listOf(unshared)).pages[0].slices.single())
        val ignored = assertIs<ImageSlice>(layout(listOf(image), typography = without(StyleAspect.Spacing)).pages[0].slices.single())
        assertEquals(natural.width, ignored.width)
        assertTrue(natural.width > slice.width)
    }
}
