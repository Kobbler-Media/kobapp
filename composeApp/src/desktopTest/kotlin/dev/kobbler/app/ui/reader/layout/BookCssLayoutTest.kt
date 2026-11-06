package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.kobbler.app.ui.reader.layout.LayoutRig.layout
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BorderLine
import dev.kobbler.app.ui.reader.pack.Container
import dev.kobbler.app.ui.reader.pack.Measure
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import dev.kobbler.app.ui.reader.pack.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookCssLayoutTest {

    private fun without(aspect: StyleAspect) = LayoutRig.typography.copy(book = BookStyle.Book.with(aspect, book = false))

    private fun firstText(blocks: List<Block>, styles: List<PackTextStyle> = listOf(PackTextStyle()), typography: ReaderTypography = LayoutRig.typography) =
        layout(blocks, styles = styles, typography = typography).pages[0].slices.filterIsInstance<TextSlice>().first()

    private fun TextSlice.spanAt(offset: Int): SpanStyle = layout.layoutInput.text.spanStyles
        .filter { offset in it.start until it.end }.fold(SpanStyle()) { acc, r -> acc.merge(r.item) }

    @Test
    fun `word spacing widens only the spaces, and only with the book's spacing`() {
        val styles = listOf(PackTextStyle(), PackTextStyle(wordSpacing = 1f))
        fun right(style: Int, typography: ReaderTypography = LayoutRig.typography) =
            firstText(listOf(Block(spans = listOf(Span("a b c d", style)))), styles, typography).layout.getLineRight(0)
        assertEquals(right(0) + 3 * 16f, right(1), 1f)
        assertEquals(right(0), right(1, without(StyleAspect.Spacing)), 0.5f)
        val letters = firstText(listOf(Block(spans = listOf(Span("abcd", 1)))), styles)
        assertEquals(firstText(listOf(Block(spans = listOf(Span("abcd", 0)))), styles).layout.getLineRight(0), letters.layout.getLineRight(0), 0.5f)
    }

    @Test
    fun `a block centred by its margins keeps the book's width`() {
        fun slice(measure: Measure, typography: ReaderTypography = LayoutRig.typography) =
            firstText(listOf(Block(spans = listOf(Span("Centred.")), centred = measure)), typography = typography)
        val em = slice(Measure(em = 10f))
        assertEquals(160f, em.width)
        assertEquals(120f, em.left)
        val half = slice(Measure(fraction = 0.5f))
        assertEquals(200f, half.width)
        assertEquals(100f, half.left)
        val mine = slice(Measure(em = 10f), without(StyleAspect.Spacing))
        assertEquals(LayoutRig.Width.toFloat(), mine.width)
    }

    @Test
    fun `a centred box sits around its centred text`() {
        val line = BorderLine(width = 0.125f)
        val box = Container(top = line, right = line, bottom = line, left = line, centred = Measure(em = 10f), inner = 2f)
        val block = Block(spans = listOf(Span("Boxed.")), containers = listOf(box), centred = Measure(em = 10f), insetStart = 1f, insetEnd = 1f)
        val band = layout(listOf(block)).pages[0].boxes.single()
        assertEquals(LayoutRig.Width / 2f - (160f + 32f) / 2, band.left)
        assertEquals(LayoutRig.Width / 2f + (160f + 32f) / 2, band.right)
    }

    @Test
    fun `a vertical-align length shifts the span's baseline`() {
        val slice = firstText(listOf(Block(spans = listOf(Span("x"), Span("up", 1)))), listOf(PackTextStyle(), PackTextStyle(shift = 0.5f)))
        assertEquals(BaselineShift(0.5f), slice.spanAt(1).baselineShift)
    }

    @Test
    fun `an inline background tints the span, and only with the book's colours`() {
        val styles = listOf(PackTextStyle(), PackTextStyle(background = "#ffff00"), PackTextStyle(background = "#ffffff"))
        val blocks = listOf(Block(spans = listOf(Span("a"), Span("marked", 1), Span("white", 2))))
        val tinted = firstText(blocks, styles).spanAt(1).background
        assertEquals(Color(1f, 1f, 0f, tinted.alpha), tinted)
        assertTrue(tinted.alpha in 0.2f..0.6f, "$tinted")
        assertEquals(Color.Unspecified, firstText(blocks, styles).spanAt(7).background, "white paper is no tint")
        assertEquals(Color.Unspecified, firstText(blocks, styles, without(StyleAspect.Colours)).spanAt(1).background)
    }

    @Test
    fun `small caps are made from capitals when the font has none`() {
        val styles = listOf(PackTextStyle(smallCaps = true))
        val made = buildPackText(listOf(Span("Small Caps")), styles, LayoutRig.typography, smallCapsMissing = { true })
        assertEquals("SMALL CAPS", made.text)
        val small = made.spanStyles.filter { it.item.fontSize != TextUnit.Unspecified && it.item.fontSize < 16.sp }
        assertEquals(listOf(1 until 5, 7 until 10), small.map { it.start until it.end })
        assertTrue(made.spanStyles.none { it.item.fontFeatureSettings == "smcp" })
        val native = buildPackText(listOf(Span("Small Caps")), styles, LayoutRig.typography)
        assertEquals("Small Caps", native.text)
        assertTrue(native.spanStyles.any { it.item.fontFeatureSettings == "smcp" })
    }

    @Test
    fun `a font lacks small caps when asking for them changes nothing`() {
        assertTrue(lacksSmallCaps { smcp -> if (smcp) 100f else 100f })
        assertFalse(lacksSmallCaps { smcp -> if (smcp) 120f else 100f })
    }
}
