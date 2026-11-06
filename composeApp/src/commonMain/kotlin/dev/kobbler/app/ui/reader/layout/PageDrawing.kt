package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import dev.kobbler.app.ui.reader.ReaderTheme
import dev.kobbler.app.ui.reader.pack.Frame

internal fun DrawScope.drawFrames(frames: List<FrameBand>, theme: ReaderTheme) {
    val pad = 4.dp.toPx()
    for (band in frames) when (band.frame) {
        Frame.Quote -> drawRect(
            color = theme.textColor.copy(alpha = 0.25f),
            topLeft = Offset(band.left, band.top),
            size = Size(3.dp.toPx(), band.bottom - band.top),
        )
        Frame.Aside -> drawRoundRect(
            color = theme.textColor.copy(alpha = 0.06f),
            topLeft = Offset(band.left, band.top - pad),
            size = Size(band.right - band.left, band.bottom - band.top + 2 * pad),
            cornerRadius = CornerRadius(pad),
        )
        Frame.Figure -> {}
    }
}

private fun DrawScope.drawBookTable(slice: TableSlice, theme: ReaderTheme, book: BookStyle) {
    val em = theme.fontSize.toPx()
    val dy = slice.top - slice.rowsTop
    for (r in slice.rows) {
        val row = slice.table.rows[r]
        val top = row.top + dy
        if (row.header) drawRect(theme.textColor.copy(alpha = 0.06f), Offset(slice.left, top), Size(slice.width, row.height))
        for (cell in row.cells) {
            val left = slice.left + cell.left
            cell.borders?.let { drawSides(it, left, top, left + cell.width, top + row.height, theme, book.colours, em, onEdge = slice.table.collapse) }
            for (item in cell.items) if (item is CellItem.Text) drawText(item.layout, topLeft = Offset(left + item.dx, top + item.dy))
        }
    }
}

internal fun DrawScope.drawCursorBand(slices: List<PageSlice>, color: Color) {
    for (slice in slices) drawRect(
        color = color,
        topLeft = Offset(0f, slice.top),
        size = Size(size.width, slice.height),
    )
}

internal fun DrawScope.drawTextSlice(slice: TextSlice, highlights: List<TextHighlight>) {
    val origin = Offset(slice.left, slice.top - slice.layoutTop)
    clipRect(left = 0f, top = slice.top, right = size.width, bottom = slice.bottom) {
        for (h in highlights) {
            if (h.block != slice.blockIndex) continue
            val start = maxOf(h.range.first, slice.startOffset)
            val end = minOf(h.range.last + 1, slice.endOffset)
            if (end <= start) continue
            translate(origin.x, origin.y) { drawPath(slice.layout.getPathForRange(start - slice.base, end - slice.base), h.color) }
        }
        drawText(slice.layout, topLeft = origin)
    }
    slice.marker?.let { drawText(it.layout, topLeft = Offset(it.left, it.top)) }
}

internal fun DrawScope.drawRule(slice: RuleSlice, theme: ReaderTheme, colours: Boolean) {
    slice.style?.let { return drawBookRule(slice, it, theme, colours) }
    val y = slice.top + slice.height / 2
    drawLine(
        color = theme.textColor.copy(alpha = 0.4f),
        start = Offset(slice.left, y),
        end = Offset(slice.left + slice.width, y),
        strokeWidth = 1.dp.toPx(),
    )
}

internal fun DrawScope.drawTableSlice(slice: TableSlice, theme: ReaderTheme, book: BookStyle) {
    if (slice.table.bookBorders) return drawBookTable(slice, theme, book)
    val grid = theme.textColor.copy(alpha = 0.2f)
    val stroke = 1.dp.toPx()
    val dy = slice.top - slice.rowsTop
    for (r in slice.rows) {
        val row = slice.table.rows[r]
        val top = row.top + dy
        if (row.header) drawRect(theme.textColor.copy(alpha = 0.06f), Offset(slice.left, top), Size(slice.width, row.height))
        drawLine(grid, Offset(slice.left, top + row.height), Offset(slice.left + slice.width, top + row.height), stroke)
        for ((c, cell) in row.cells.withIndex()) {
            if (c > 0) drawLine(grid, Offset(slice.left + cell.left, top), Offset(slice.left + cell.left, top + row.height), stroke)
            for (item in cell.items) if (item is CellItem.Text) {
                drawText(item.layout, topLeft = Offset(slice.left + cell.left + item.dx, top + item.dy))
            }
        }
    }
    drawLine(grid, Offset(slice.left, slice.top), Offset(slice.left + slice.width, slice.top), stroke)
}
