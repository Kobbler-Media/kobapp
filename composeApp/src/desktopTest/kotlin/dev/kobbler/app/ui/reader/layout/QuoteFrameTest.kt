package dev.kobbler.app.ui.reader.layout

import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.Frame
import dev.kobbler.app.ui.reader.pack.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An epigraph: the quote and its attribution, indented differently inside one blockquote. */
class QuoteFrameTest {

    private val epigraph = listOf(
        Block(spans = listOf(Span("Sometimes it is strength to walk away.")), insetStart = 2f, frame = Frame.Quote),
        Block(spans = listOf(Span("—From The Way of Kings")), insetStart = 3f, frame = Frame.Quote),
        Block(spans = listOf(Span("The chapter begins."))),
    )

    private fun bands(bookDecorations: Boolean, blocks: List<Block> = epigraph) = LayoutRig.layout(
        blocks,
        typography = LayoutRig.typography.copy(book = BookStyle.Book.with(StyleAspect.Decorations, bookDecorations)),
    ).pages[0].frames

    @Test
    fun `the book's own styling decides how a quote looks`() {
        assertTrue(bands(bookDecorations = true).none { it.frame == Frame.Quote }, "drew a quote bar the book never asked for")
    }

    @Test
    fun `without the book's styling one bar runs the length of the quote`() {
        val quote = bands(bookDecorations = false).filter { it.frame == Frame.Quote }
        assertEquals(1, quote.size, "the quote was split into $quote")
        val body = LayoutRig.layout(epigraph).pages[0].slices
        assertEquals(body[0].top, quote[0].top)
        assertEquals(body[1].bottom, quote[0].bottom)
    }

    @Test
    fun `a quote the book leaves at the browser's defaults keeps the bar`() {
        val plain = epigraph.map { if (it.frame == Frame.Quote) it.copy(framePlain = true) else it }
        assertEquals(1, bands(bookDecorations = true, plain).count { it.frame == Frame.Quote })
    }

    @Test
    fun `asides keep their panel either way`() {
        val aside = listOf(Block(spans = listOf(Span("A sidebar.")), frame = Frame.Aside))
        for (book in listOf(true, false)) {
            assertEquals(1, bands(book, aside).count { it.frame == Frame.Aside }, "book decorations $book")
        }
    }
}
