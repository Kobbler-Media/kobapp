package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.kobbler.app.ui.reader.ReaderTheme
import dev.kobbler.app.ui.reader.layout.LayoutRig.layout
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockKind
import dev.kobbler.app.ui.reader.pack.BorderLine
import dev.kobbler.app.ui.reader.pack.Borders
import dev.kobbler.app.ui.reader.pack.Container
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import dev.kobbler.app.ui.reader.pack.RuleImage
import dev.kobbler.app.ui.reader.pack.RuleStyle
import dev.kobbler.app.ui.reader.pack.Span
import dev.kobbler.app.ui.reader.pack.Table
import dev.kobbler.app.ui.reader.pack.TableCell
import dev.kobbler.app.ui.reader.pack.TableRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookCssDrawingTest {

    private val theme = ReaderTheme.Light
    private val black = BorderLine(width = 0.125f, color = "#000000")

    private fun paint(width: Int = LayoutRig.Width, height: Int = 200, draw: DrawScope.() -> Unit): PixelMap {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat()), draw)
        return bitmap.toPixelMap()
    }

    private fun tableSlice(book: BookStyle): TableSlice {
        val cell = TableCell(listOf(Block(spans = listOf(Span("a")))), borders = Borders(black, black, black, black))
        val table = Block(kind = BlockKind.Table, table = Table(listOf(TableRow(cells = listOf(cell, cell))), collapse = true))
        return layout(listOf(table), typography = LayoutRig.typography.copy(book = book)).pages[0].slices.single() as TableSlice
    }

    @Test
    fun `a table draws the book's cell borders in place of the app's grid`() {
        val book = tableSlice(BookStyle.Book)
        val px = paint { drawTableSlice(book, theme, BookStyle.Book) }
        val middle = (book.top + book.height / 2).toInt()
        assertEquals(1f, px[0, middle].alpha, 0.01f, "the book's outer left border")
        val mine = tableSlice(BookStyle.Book.with(StyleAspect.Decorations, book = false))
        val grid = paint { drawTableSlice(mine, theme, BookStyle.Book) }
        assertEquals(0f, grid[0, middle].alpha, "the app's grid has no outer side")
    }

    @Test
    fun `a box with round corners leaves its corners open`() {
        val square = Container(top = black, right = black, bottom = black, left = black, background = "#000000")
        fun corner(box: Container) = paint(120, 100) {
            drawBoxes(listOf(BoxBand(box, 10f, 110f, 10f, 90f, openTop = false, openBottom = false)), theme, colours = true)
        }[11, 11].alpha
        assertTrue(corner(square) > 0.5f)
        assertEquals(0f, corner(square.copy(radius = listOf(1f, 1f, 1f, 1f))))
        assertTrue(corner(square.copy(radius = listOf(0f, 1f, 1f, 1f))) > 0.5f, "only the rounded corners open")
    }

    @Test
    fun `an inline border outlines its words`() {
        val styles = listOf(PackTextStyle(), PackTextStyle(borders = Borders(black, black, black, black)))
        val block = Block(spans = listOf(Span("plain "), Span("boxed", 1), Span(" plain")))
        val slice = layout(listOf(block), styles = styles).pages[0].slices.single() as TextSlice
        val start = slice.left + slice.layout.getHorizontalPosition(6, usePrimaryDirection = true)
        val middle = (slice.top + slice.height / 2).toInt()
        val drawn = paint { drawSpanBorders(slice, block, styles, LayoutRig.typography) }
        assertTrue(drawn[start.toInt(), middle].alpha > 0.5f, "no outline at the span's start")
        assertEquals(0f, drawn[2, middle].alpha)
        val mine = LayoutRig.typography.copy(book = BookStyle.Book.with(StyleAspect.Decorations, book = false))
        assertEquals(0f, paint { drawSpanBorders(slice, block, styles, mine) }[start.toInt(), middle].alpha)
    }

    @Test
    fun `an inline border draws only the sides the book set`() {
        val styles = listOf(PackTextStyle(), PackTextStyle(borders = Borders(bottom = black)))
        val block = Block(spans = listOf(Span("plain "), Span("term", 1), Span(" plain")))
        val slice = layout(listOf(block), styles = styles).pages[0].slices.single() as TextSlice
        val start = (slice.left + slice.layout.getBoundingBox(6).left).toInt()
        val bottom = (slice.top + slice.height).toInt() - 1
        val middle = (slice.top + slice.height / 2).toInt()
        val drawn = paint { drawSpanBorders(slice, block, styles, LayoutRig.typography) }
        assertTrue(drawn[start + 4, bottom].alpha > 0.5f, "no underline")
        assertEquals(0f, drawn[start, middle].alpha, "drew a left side the book never set")
    }

    @Test
    fun `a span broken over lines closes its box only where it starts and ends`() {
        val styles = listOf(PackTextStyle(), PackTextStyle(borders = Borders(black, black, black, black)))
        val block = Block(spans = listOf(Span("lead in boxed words that wrap here", 1)))
        val slice = layout(listOf(block), width = 120, styles = styles).pages[0].slices.single() as TextSlice
        assertTrue(slice.lastLine > slice.firstLine, "the span did not wrap")
        val layout = slice.layout
        fun mid(line: Int) = (slice.top + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2 - slice.layoutTop).toInt()
        val drawn = paint(width = 120) { drawSpanBorders(slice, block, styles, LayoutRig.typography) }
        val firstRight = (slice.left + layout.getLineRight(0)).toInt() - 1
        val lastLeft = (slice.left + layout.getLineLeft(slice.lastLine)).toInt()
        assertTrue(drawn[(slice.left + layout.getLineLeft(0)).toInt(), mid(0)].alpha > 0.5f, "the box does not open on its first line")
        assertEquals(0f, drawn[firstRight, mid(0)].alpha, "closed the box at a line break")
        assertEquals(0f, drawn[lastLeft, mid(slice.lastLine)].alpha, "reopened the box after a line break")
    }

    @Test
    fun `a rule drawn as a picture is laid out as the picture`() {
        val ornament = RuleImage("stars.png", width = 1200, height = 800)
        fun rule(image: RuleImage, typography: ReaderTypography = LayoutRig.typography) =
            layout(listOf(Block(kind = BlockKind.Rule, rule = RuleStyle(height = 1f, image = image))), typography = typography)
                .pages[0].slices.single() as RuleSlice
        val once = rule(ornament)
        assertEquals(16f, once.height)
        assertEquals(24f, once.width)
        assertEquals((LayoutRig.Width - 24f) / 2, once.left)
        assertEquals(listOf(Rect(once.left, once.top, once.left + 24f, once.top + 16f)), once.imageTiles())
        val tiled = rule(ornament.copy(repeat = true))
        assertEquals(LayoutRig.Width.toFloat(), tiled.width)
        assertEquals(17, tiled.imageTiles().size)
        val mine = rule(ornament, LayoutRig.typography.copy(book = BookStyle.Book.with(StyleAspect.Decorations, book = false)))
        assertEquals(null, mine.style)
        assertTrue(mine.imageTiles().isEmpty())
    }
}
