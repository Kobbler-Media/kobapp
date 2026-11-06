package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.text.SpanStyle
import dev.kobbler.app.ui.reader.layout.LayoutRig.layout
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockKind
import dev.kobbler.app.ui.reader.pack.BorderLine
import dev.kobbler.app.ui.reader.pack.Container
import dev.kobbler.app.ui.reader.pack.DropCap
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import dev.kobbler.app.ui.reader.pack.RuleStyle
import dev.kobbler.app.ui.reader.pack.Span
import dev.kobbler.app.ui.reader.pack.TextAlignDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Every aspect of the book's styling can be given up on its own, and takes nothing else with it. */
class StyleAspectTest {

    private val styles = listOf(
        PackTextStyle(),
        PackTextStyle(family = "serif"),
        PackTextStyle(size = 2f),
        PackTextStyle(color = "#ff0000"),
        PackTextStyle(letterSpacing = 0.1f),
        PackTextStyle(align = TextAlignDto.Center, indent = 2f),
        PackTextStyle(dropcap = DropCap(lines = 3, chars = 1)),
    )

    private val line = BorderLine(width = 0.125f)
    private val blocks = listOf(
        Block(spans = listOf(Span("serif", 1), Span(" big", 2), Span(" red", 3), Span(" wide", 4))),
        Block(style = 5, spaceBefore = 3f, spans = listOf(Span("centred"))),
        Block(spans = listOf(Span("boxed")), containers = listOf(Container(top = line, bottom = line))),
        Block(kind = BlockKind.Rule, rule = RuleStyle(line, length = 0.5f)),
        Block(style = 6, spans = listOf(Span("Once"))),
    )

    private fun probes(book: BookStyle): Map<StyleAspect, List<Any?>> {
        val page = layout(blocks, height = 2000, styles = styles, typography = LayoutRig.typography.copy(book = book)).pages[0]
        val text = page.slices.filterIsInstance<TextSlice>()
        fun TextSlice.at(offset: Int): SpanStyle = layout.layoutInput.text.spanStyles
            .filter { offset in it.start until it.end }.fold(SpanStyle()) { acc, r -> acc.merge(r.item) }
        val (first, centred) = text
        val dropped = text.single { it.blockIndex == 4 }
        return mapOf(
            StyleAspect.Typeface to listOf(first.at(0).fontFamily),
            StyleAspect.Sizes to listOf(first.at(7).fontSize),
            StyleAspect.Colours to listOf(first.at(11).color),
            StyleAspect.Spacing to listOf(first.at(16).letterSpacing, centred.top - first.bottom, centred.layout.layoutInput.style.textIndent),
            StyleAspect.Decorations to listOf(page.boxes.size, page.slices.filterIsInstance<RuleSlice>().single().style, dropped.at(0).fontSize),
            StyleAspect.Alignment to listOf(centred.layout.layoutInput.style.textAlign),
        )
    }

    @Test
    fun `each aspect switches only its own effect`() {
        val book = probes(BookStyle.Book)
        for (aspect in StyleAspect.entries) {
            val without = probes(BookStyle.Book.with(aspect, book = false))
            for (other in StyleAspect.entries) {
                if (other == aspect) assertNotEquals(book[other], without[other], "giving up $aspect left it as the book had it")
                else assertEquals(book[other], without[other], "giving up $aspect changed $other")
            }
        }
    }

    @Test
    fun `my style gives every aspect up`() {
        val book = probes(BookStyle.Book)
        val mine = probes(BookStyle.Mine)
        for (aspect in StyleAspect.entries) assertNotEquals(book[aspect], mine[aspect], "$aspect")
    }
}
