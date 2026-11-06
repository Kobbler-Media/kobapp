package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.kobbler.app.ui.reader.ReaderTheme
import dev.kobbler.app.ui.reader.pack.BorderLine
import dev.kobbler.app.ui.reader.pack.Container
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoxDrawingTest {

    private val black = BorderLine(width = 0.25f, color = "#000000")
    private val box = Container(top = black, right = black, bottom = black, left = black, background = "#808080")

    private fun draw(band: BoxBand, theme: ReaderTheme, colours: Boolean = true): PixelMap {
        val bitmap = ImageBitmap(120, 100)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(120f, 100f)) {
            drawBoxes(listOf(band), theme, colours)
        }
        return bitmap.toPixelMap()
    }

    private fun band(openTop: Boolean = false, openBottom: Boolean = false) =
        BoxBand(box, left = 10f, right = 110f, top = 10f, bottom = 90f, openTop = openTop, openBottom = openBottom)

    @Test
    fun `borders draw on every closed side and the fill is a shade of the text colour`() {
        val px = draw(band(), ReaderTheme.Light)
        for ((x, y) in listOf(11 to 50, 108 to 50, 60 to 11, 60 to 88)) {
            assertEquals(1f, px[x, y].alpha, 0.01f, "border at $x,$y")
            assertTrue(px[x, y].luminance() < 0.05f, "border at $x,$y is ${px[x, y]}")
        }
        val fill = px[60, 50]
        assertTrue(fill.alpha in 0.05f..0.3f, "fill $fill")
        assertEquals(0f, px[5, 50].alpha, "drew outside the box")
    }

    @Test
    fun `a side broken by the page stays open`() {
        val px = draw(band(openTop = true, openBottom = true), ReaderTheme.Light)
        assertTrue(px[60, 11].alpha < 0.5f, "top edge drawn across a page break")
        assertTrue(px[60, 88].alpha < 0.5f, "bottom edge drawn across a page break")
        assertEquals(1f, px[11, 50].alpha, 0.01f)
    }

    @Test
    fun `a book's black border turns to the text colour on a dark theme`() {
        val theme = ReaderTheme.Light.copy(backgroundColor = Color(0xFF121212), textColor = Color(0xFFE0E0E0))
        assertTrue(draw(band(), theme)[11, 50].luminance() > 0.5f)
    }

    @Test
    fun `without the book's colours a box keeps its lines in mine and drops its fill`() {
        val red = BorderLine(width = 0.25f, color = "#ff0000")
        val band = band().copy(container = box.copy(left = red))
        assertTrue(draw(band, ReaderTheme.Light)[11, 50].red > 0.9f)
        val mine = draw(band, ReaderTheme.Light, colours = false)
        assertEquals(ReaderTheme.Light.textColor, mine[11, 50])
        assertEquals(0f, mine[60, 50].alpha, "kept the book's fill")
    }
}
