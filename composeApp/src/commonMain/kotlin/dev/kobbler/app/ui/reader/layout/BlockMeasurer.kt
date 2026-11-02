package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockKind
import dev.kobbler.app.ui.reader.pack.FixedPageDto
import dev.kobbler.app.ui.reader.pack.Hyphenation
import dev.kobbler.app.ui.reader.pack.ImageRef
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import dev.kobbler.app.ui.reader.pack.Span
import dev.kobbler.app.ui.reader.pack.Table
import dev.kobbler.app.ui.reader.pack.TextAlignDto
import dev.kobbler.app.ui.reader.pack.styleAt
import kotlin.math.abs
import kotlin.math.min

internal sealed interface MeasuredBlock {
    class Text(val layout: TextLayoutResult, val left: Float, val marker: TextLayoutResult?, val markerLeft: Float) : MeasuredBlock
    class Image(val image: ImageRef, val left: Float, val width: Float, val height: Float) : MeasuredBlock
    class Rule(val left: Float, val width: Float, val height: Float) : MeasuredBlock
    class Table(val table: TableLayout, val left: Float) : MeasuredBlock
    class Fixed(val fixed: FixedPageDto, val left: Float, val top: Float, val width: Float, val height: Float) : MeasuredBlock
    data object Empty : MeasuredBlock
}

internal class BlockMeasurer(
    private val styles: List<PackTextStyle>,
    private val typography: ReaderTypography,
    private val pageSize: IntSize,
    private val density: Density,
    private val measurer: TextMeasurer,
) {
    private val theme = typography.theme
    val basePx: Float = with(density) { theme.fontSize.toPx() }
    val padding: Float = with(density) { theme.horizontalPadding.dp.toPx() }
    private val contentWidth = (pageSize.width - 2 * padding).coerceAtLeast(1f)

    fun styleOf(block: Block): PackTextStyle = styles.styleAt(block.style)

    fun insetLeft(block: Block): Float = padding + block.insetStart * basePx

    fun insetRight(block: Block): Float = pageSize.width - padding - block.insetEnd * basePx

    private fun availableWidth(block: Block): Float =
        (contentWidth - (block.insetStart + block.insetEnd) * basePx).coerceAtLeast(contentWidth * MinColumnFraction)

    fun measure(block: Block): MeasuredBlock = when (block.kind) {
        BlockKind.Text, BlockKind.Heading, BlockKind.Code ->
            if (block.text.isEmpty()) MeasuredBlock.Empty else measureText(block)
        BlockKind.Image -> block.image?.let { image ->
            val width = availableWidth(block)
            val (w, h) = fitImage(image, width, pageSize.height.toFloat())
            MeasuredBlock.Image(image, insetLeft(block) + (width - w) / 2, w, h)
        } ?: MeasuredBlock.Empty
        BlockKind.Rule -> {
            val width = availableWidth(block)
            val w = width * RuleFraction
            MeasuredBlock.Rule(insetLeft(block) + (width - w) / 2, w, basePx)
        }
        BlockKind.Table -> block.table?.let { measureTable(it, availableWidth(block)) }
            ?.let { MeasuredBlock.Table(it, insetLeft(block)) } ?: MeasuredBlock.Empty
        BlockKind.FixedPage -> block.fixed?.let { fixed ->
            val scale = min(pageSize.width / fixed.pageWidth, pageSize.height / fixed.pageHeight)
            val w = fixed.pageWidth * scale
            val h = fixed.pageHeight * scale
            MeasuredBlock.Fixed(fixed, (pageSize.width - w) / 2, (pageSize.height - h) / 2, w, h)
        } ?: MeasuredBlock.Empty
    }

    private fun measureText(block: Block): MeasuredBlock.Text {
        val style = styleOf(block)
        val indentPx = (style.indent ?: 0f) * (style.size ?: 1f) * basePx
        val hang = if (indentPx < 0) min(abs(indentPx), block.insetStart * basePx) else 0f
        val width = availableWidth(block) + hang
        val layout = layoutText(block, style, width, hang)
        val marker = block.marker?.let { measureMarker(it, block) }
        val markerLeft = marker?.let {
            (insetLeft(block) - hang - it.size.width - basePx * MarkerGap).coerceAtLeast(0f)
        } ?: 0f
        return MeasuredBlock.Text(layout, insetLeft(block) - hang, marker, markerLeft)
    }

    fun layoutText(block: Block, style: PackTextStyle, width: Float, hang: Float = 0f): TextLayoutResult {
        val text = buildPackText(
            block.spans, styles, typography,
            dropCap = style.dropcap.takeIf { block.kind == BlockKind.Text },
        )
        return measurer.measure(
            text = text,
            style = paragraphStyle(block, style, hang),
            constraints = Constraints.fixedWidth(width.toInt().coerceAtLeast(1)),
            skipCache = true,
        )
    }

    private fun measureMarker(marker: String, block: Block): TextLayoutResult {
        val markerSpan = Span(marker, block.spans.firstOrNull()?.style ?: block.style)
        return measurer.measure(
            text = buildPackText(listOf(markerSpan), styles, typography),
            style = TextStyle(color = theme.textColor, fontFamily = theme.fontFamily),
            softWrap = false,
            skipCache = true,
        )
    }

    private fun paragraphStyle(block: Block, style: PackTextStyle, hang: Float): TextStyle {
        val size = style.size ?: 1f
        val indentEm = style.indent ?: 0f
        val publisher = typography.publisherStyles
        val lineHeight = style.lineHeight?.takeIf { publisher } ?: typography.lineHeightMultiplier
        val hangSp = with(density) { hang.toSp() }
        return TextStyle(
            color = theme.textColor,
            fontSize = (theme.fontSize.value * size).sp,
            fontFamily = if (block.kind == BlockKind.Code) FontFamily.Monospace else theme.fontFamily,
            lineHeight = lineHeight.em,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
            textAlign = alignOf(block, style),
            textIndent = if (indentEm < 0) TextIndent(restLine = hangSp)
            else TextIndent(firstLine = (theme.fontSize.value * size * indentEm).sp),
            hyphens = if (block.kind == BlockKind.Code || style.hyphens == Hyphenation.None || style.hyphens == Hyphenation.Manual) {
                Hyphens.None
            } else Hyphens.Auto,
            lineBreak = if (block.kind == BlockKind.Code) LineBreak.Simple else LineBreak.Paragraph,
        )
    }

    private fun alignOf(block: Block, style: PackTextStyle): TextAlign {
        if (block.kind == BlockKind.Code) return TextAlign.Start
        val book = style.align?.let(::textAlignOf)
        val fallback = if (block.kind == BlockKind.Text && typography.justify) TextAlign.Justify else TextAlign.Start
        return when {
            book == null -> fallback
            typography.publisherStyles -> book
            block.kind == BlockKind.Heading -> book
            else -> fallback
        }
    }

    private fun measureTable(table: Table, width: Float): TableLayout? {
        val columns = table.rows.maxOfOrNull { row -> row.cells.sumOf { it.colspan.coerceAtLeast(1) } } ?: 0
        if (columns == 0) return null
        val pad = basePx * CellPaddingEm
        val columnWidth = width / columns
        var top = 0f
        val rows = table.rows.map { row ->
            var column = 0
            val cells = row.cells.map { cell ->
                val span = cell.colspan.coerceAtLeast(1)
                val cellWidth = (columnWidth * span - 2 * pad).coerceAtLeast(1f)
                val left = column * columnWidth
                column += span
                CellLayout(left, columnWidth * span, measureCell(cell.blocks, cellWidth, pad))
            }
            val height = cells.maxOfOrNull { cell ->
                cell.items.maxOfOrNull { it.dy + itemHeight(it) }?.plus(pad) ?: (2 * pad)
            } ?: (2 * pad)
            RowLayout(row.header, top, height, cells).also { top += height }
        }
        return TableLayout(width, pad, rows)
    }

    private fun measureCell(blocks: List<Block>, width: Float, pad: Float): List<CellItem> {
        var y = pad
        val items = mutableListOf<CellItem>()
        for ((i, block) in blocks.withIndex()) {
            if (i > 0) y += block.spaceBefore * basePx
            when (block.kind) {
                BlockKind.Text, BlockKind.Heading, BlockKind.Code -> if (block.text.isNotEmpty()) {
                    val layout = layoutText(block, styleOf(block), width)
                    items += CellItem.Text(layout, pad, y)
                    y += layout.size.height
                }
                BlockKind.Image -> block.image?.let { image ->
                    val (w, h) = fitImage(image, width, pageSize.height / 2f)
                    items += CellItem.Image(image, pad, y, w, h)
                    y += h
                }
                else -> {}
            }
        }
        return items
    }

    private fun itemHeight(item: CellItem): Float = when (item) {
        is CellItem.Text -> item.layout.size.height.toFloat()
        is CellItem.Image -> item.height
    }

    private fun fitImage(image: ImageRef, maxWidth: Float, maxHeight: Float): Pair<Float, Float> {
        val w = image.width?.takeIf { it > 0 }?.toFloat()
        val h = image.height?.takeIf { it > 0 }?.toFloat()
        if (w == null || h == null) {
            val fallbackH = min(maxWidth * FallbackAspect, maxHeight)
            return maxWidth to fallbackH
        }
        val scale = minOf(density.density, maxWidth / w, maxHeight / h)
        return w * scale to h * scale
    }

    private companion object {
        const val MinColumnFraction = 0.3f
        const val RuleFraction = 0.3f
        const val MarkerGap = 0.4f
        const val CellPaddingEm = 0.3f
        const val FallbackAspect = 2f / 3f
    }
}

internal fun textAlignOf(align: TextAlignDto): TextAlign = when (align) {
    TextAlignDto.Start -> TextAlign.Start
    TextAlignDto.End -> TextAlign.End
    TextAlignDto.Left -> TextAlign.Left
    TextAlignDto.Right -> TextAlign.Right
    TextAlignDto.Center -> TextAlign.Center
    TextAlignDto.Justify -> TextAlign.Justify
}
