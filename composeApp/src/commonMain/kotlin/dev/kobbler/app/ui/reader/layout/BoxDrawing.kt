package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import dev.kobbler.app.ui.reader.ReaderTheme
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BorderLine
import dev.kobbler.app.ui.reader.pack.Borders
import dev.kobbler.app.ui.reader.pack.LineStyle
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import dev.kobbler.app.ui.reader.pack.RuleStyle
import dev.kobbler.app.ui.reader.pack.styleAt

internal fun DrawScope.drawBoxes(boxes: List<BoxBand>, theme: ReaderTheme, colours: Boolean) {
    val em = theme.fontSize.toPx()
    for (band in boxes) {
        val box = band.container
        val corners = box.radius?.takeIf { r -> r.size == 4 && r.any { it > 0f } }?.let { r ->
            val top = if (band.openTop) 0f else 1f
            val bottom = if (band.openBottom) 0f else 1f
            listOf(r[0] * top, r[1] * top, r[2] * bottom, r[3] * bottom).map { it * em }
        }
        val sides = Borders(box.top.takeIf { !band.openTop }, box.right, box.bottom.takeIf { !band.openBottom }, box.left)
        shadeOf(box.background?.takeIf { colours }, theme)?.let { shade ->
            if (corners == null) drawRect(shade, Offset(band.left, band.top), Size(band.right - band.left, band.bottom - band.top))
            else drawPath(roundPath(band.left, band.top, band.right, band.bottom, corners), shade)
        }
        val uniform = sides.top?.takeIf { it == sides.right && it == sides.bottom && it == sides.left }
        if (corners != null && uniform != null) drawRounded(uniform, band, corners, theme, colours, em)
        else drawSides(sides, band.left, band.top, band.right, band.bottom, theme, colours, em)
    }
}

/** A collapsed table's borders sit on the edge between two cells, so both cells' lines coincide. */
internal fun DrawScope.drawSides(
    sides: Borders,
    l: Float,
    t: Float,
    r: Float,
    b: Float,
    theme: ReaderTheme,
    colours: Boolean,
    em: Float,
    onEdge: Boolean = false,
) {
    sides.top?.let { edge(it, theme, colours, em, Offset(l, t), Offset(r, t), Offset(0f, 1f), onEdge) }
    sides.bottom?.let { edge(it, theme, colours, em, Offset(l, b), Offset(r, b), Offset(0f, -1f), onEdge) }
    sides.left?.let { edge(it, theme, colours, em, Offset(l, t), Offset(l, b), Offset(1f, 0f), onEdge) }
    sides.right?.let { edge(it, theme, colours, em, Offset(r, t), Offset(r, b), Offset(-1f, 0f), onEdge) }
}

internal fun DrawScope.drawBookRule(slice: RuleSlice, style: RuleStyle, theme: ReaderTheme, colours: Boolean) {
    val line = style.line?.takeIf { style.image == null } ?: return
    val em = theme.fontSize.toPx()
    val y = slice.top + (slice.height - thickness(line, em)) / 2
    edge(line, theme, colours, em, Offset(slice.left, y), Offset(slice.left + slice.width, y), Offset(0f, 1f))
}

internal fun DrawScope.drawSpanBorders(slice: TextSlice, block: Block, styles: List<PackTextStyle>, typography: ReaderTypography) {
    if (!typography.book.decorations) return
    val em = typography.theme.fontSize.toPx()
    val layout = slice.layout
    val dy = slice.top - slice.layoutTop
    for ((i, span) in block.spans.withIndex()) {
        val sides = styles.styleAt(span.style).borders ?: continue
        val spanStart = block.spanStart(i)
        val spanEnd = spanStart + span.text.length
        val start = maxOf(spanStart, slice.startOffset) - slice.base
        val end = minOf(spanEnd, slice.endOffset) - slice.base
        for (row in slice.firstLine..slice.lastLine) {
            val a = maxOf(start, layout.getLineStart(row))
            val b = minOf(end, layout.getLineEnd(row, visibleEnd = true))
            if (b <= a) continue
            drawSides(
                sides.copy(left = sides.left.takeIf { a + slice.base == spanStart }, right = sides.right.takeIf { b + slice.base == spanEnd }),
                slice.left + layout.getBoundingBox(a).left, layout.getLineTop(row) + dy,
                slice.left + layout.getBoundingBox(b - 1).right, layout.getLineBottom(row) + dy,
                typography.theme, typography.book.colours, em,
            )
        }
    }
}

private fun DrawScope.drawRounded(line: BorderLine, band: BoxBand, corners: List<Float>, theme: ReaderTheme, colours: Boolean, em: Float) {
    val width = thickness(line, em)
    val color = lineColor(line.color?.takeIf { colours }, theme)
    fun ring(inset: Float, w: Float, effect: PathEffect? = null) {
        val path = roundPath(band.left + inset, band.top + inset, band.right - inset, band.bottom - inset, corners.map { (it - inset).coerceAtLeast(0f) })
        drawPath(path, color, style = Stroke(w, pathEffect = effect))
    }
    when (line.style) {
        LineStyle.Solid -> ring(width / 2, width)
        LineStyle.Dashed -> ring(width / 2, width, PathEffect.dashPathEffect(floatArrayOf(3 * width, 2 * width)))
        LineStyle.Dotted -> ring(width / 2, width, PathEffect.dashPathEffect(floatArrayOf(width, width)))
        LineStyle.Double -> {
            val third = (width / 3).coerceAtLeast(1f)
            ring(third / 2, third)
            ring(width - third / 2, third)
        }
    }
}

private fun roundPath(l: Float, t: Float, r: Float, b: Float, corners: List<Float>): Path = Path().apply {
    addRoundRect(
        RoundRect(
            l, t, r, b,
            topLeftCornerRadius = CornerRadius(corners[0]),
            topRightCornerRadius = CornerRadius(corners[1]),
            bottomRightCornerRadius = CornerRadius(corners[2]),
            bottomLeftCornerRadius = CornerRadius(corners[3]),
        ),
    )
}

private fun DrawScope.edge(
    line: BorderLine,
    theme: ReaderTheme,
    colours: Boolean,
    em: Float,
    from: Offset,
    to: Offset,
    inward: Offset,
    onEdge: Boolean = false,
) {
    val width = thickness(line, em)
    val color = lineColor(line.color?.takeIf { colours }, theme)
    val base = if (onEdge) -width / 2 else 0f
    fun stroke(offset: Float, w: Float, effect: PathEffect? = null, cap: StrokeCap = StrokeCap.Butt) {
        val shift = inward * (base + offset)
        drawLine(color, from + shift, to + shift, strokeWidth = w, cap = cap, pathEffect = effect)
    }
    when (line.style) {
        LineStyle.Solid -> stroke(width / 2, width)
        LineStyle.Dashed -> stroke(width / 2, width, PathEffect.dashPathEffect(floatArrayOf(3 * width, 2 * width)))
        LineStyle.Dotted -> stroke(width / 2, width, PathEffect.dashPathEffect(floatArrayOf(0f, 2 * width)), StrokeCap.Round)
        LineStyle.Double -> {
            val third = (width / 3).coerceAtLeast(1f)
            stroke(third / 2, third)
            stroke(width - third / 2, third)
        }
    }
}

private fun thickness(line: BorderLine, em: Float): Float = (line.width * em).coerceAtLeast(1f)

private fun lineColor(color: String?, theme: ReaderTheme): Color =
    parseHexColor(color)?.takeIf { readableOn(theme.backgroundColor, it) } ?: theme.textColor

/**
 * A book's fill is chosen for white paper; drawn as is it would glare on a dark theme and
 * hide text the theme recoloured. How dark it is becomes a shade of the theme's own text colour.
 */
private fun shadeOf(background: String?, theme: ReaderTheme): Color? {
    val fill = parseHexColor(background) ?: return null
    val alpha = (1f - fill.luminance()) * ShadeStrength
    return theme.textColor.copy(alpha = alpha).takeIf { alpha >= MinShade }
}

private const val ShadeStrength = 0.2f
private const val MinShade = 0.01f
