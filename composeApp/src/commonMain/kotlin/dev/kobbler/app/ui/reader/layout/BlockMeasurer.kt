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
import dev.kobbler.app.ui.reader.pack.BlockFloat
import dev.kobbler.app.ui.reader.pack.BlockKind
import dev.kobbler.app.ui.reader.pack.FixedPageDto
import dev.kobbler.app.ui.reader.pack.FloatSide
import dev.kobbler.app.ui.reader.pack.Hyphenation
import dev.kobbler.app.ui.reader.pack.ImageRef
import dev.kobbler.app.ui.reader.pack.Measure
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import dev.kobbler.app.ui.reader.pack.RuleStyle
import dev.kobbler.app.ui.reader.pack.Span
import dev.kobbler.app.ui.reader.pack.Table
import dev.kobbler.app.ui.reader.pack.TextAlignDto
import dev.kobbler.app.ui.reader.pack.styleAt
import kotlin.math.abs
import kotlin.math.min

internal sealed interface MeasuredBlock {
    /** [layout] holds the block's text from [base] on. */
    class Text(val layout: TextLayoutResult, val left: Float, val marker: TextLayoutResult?, val markerLeft: Float, val base: Int = 0) : MeasuredBlock
    class Image(val image: ImageRef, val left: Float, val width: Float, val height: Float, val float: BlockFloat? = null, val gap: Float = 0f) : MeasuredBlock
    class Rule(val left: Float, val width: Float, val height: Float, val style: RuleStyle?) : MeasuredBlock
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
    val book: BookStyle get() = typography.book
    val basePx: Float = with(density) { theme.fontSize.toPx() }
    val padding: Float = with(density) { theme.horizontalPadding.dp.toPx() }
    private val contentWidth = (pageSize.width - 2 * padding).coerceAtLeast(1f)

    fun styleOf(block: Block): PackTextStyle = styles.styleAt(block.style)

    fun insetLeft(block: Block): Float = edgeLeft(block.insetStart)

    fun insetRight(block: Block): Float = edgeRight(block.insetEnd)

    fun edgeLeft(insetEm: Float): Float = padding + inset(insetEm) * basePx

    fun edgeRight(insetEm: Float): Float = pageSize.width - padding - inset(insetEm) * basePx

    /** The book's margins, or with spacing of my own, only as much of them as nesting needs. */
    private fun inset(em: Float): Float = if (book.spacing) em else min(em, OwnInsetMaxEm)

    fun spaceBefore(block: Block): Float =
        if (book.spacing) block.spaceBefore * basePx else with(density) { theme.paragraphSpacing.dp.toPx() }

    private fun sizeOf(style: PackTextStyle): Float = style.size?.takeIf { book.sizes } ?: 1f

    private fun indentOf(style: PackTextStyle): Float = style.indent?.takeIf { book.spacing } ?: 0f

    private fun columnWidth(block: Block): Float {
        val available = availableWidth(block)
        val measure = block.centred?.takeIf { book.spacing } ?: return available
        return min(available, measure.px(available))
    }

    private fun columnLeft(block: Block): Float = insetLeft(block) + (availableWidth(block) - columnWidth(block)) / 2

    fun Measure.px(of: Float): Float = em?.let { it * basePx } ?: fraction?.let { it * of } ?: of

    private val smallCaps = HashMap<FontFamily?, Boolean>()

    private fun smallCapsMissing(family: FontFamily?): Boolean = smallCaps.getOrPut(family) {
        lacksSmallCaps { smcp ->
            val style = TextStyle(fontFamily = family, fontSize = theme.fontSize, fontFeatureSettings = if (smcp) "smcp" else null)
            measurer.measure(SmallCapsSample, style, softWrap = false, skipCache = true).size.width.toFloat()
        }
    }

    private fun availableWidth(block: Block): Float =
        (contentWidth - (inset(block.insetStart) + inset(block.insetEnd)) * basePx).coerceAtLeast(contentWidth * MinColumnFraction)

    fun measure(block: Block): MeasuredBlock = when (block.kind) {
        BlockKind.Text, BlockKind.Heading, BlockKind.Code ->
            if (block.text.isEmpty()) MeasuredBlock.Empty else textFrom(block, 0)
        BlockKind.Image -> block.image?.let { image ->
            block.float?.takeIf { book.spacing }?.let { measureFloat(block, image, it) }?.let { return it }
            val width = columnWidth(block)
            val share = image.columnWidth?.takeIf { book.spacing }
            val (w, h) = fitImage(image, width, pageSize.height.toFloat(), share)
            MeasuredBlock.Image(image, columnLeft(block) + (width - w) / 2, w, h)
        } ?: MeasuredBlock.Empty
        BlockKind.Rule -> {
            val width = columnWidth(block)
            val style = block.rule?.takeIf { book.decorations }
            val image = style?.image
            val full = width * (style?.let { it.length ?: 1f } ?: RuleFraction)
            val height = if (image != null) (style.height ?: 1f) * basePx else maxOf(basePx, (style?.line?.width ?: 0f) * basePx)
            val w = if (image != null && !image.repeat) min(full, height * image.aspect) else full
            MeasuredBlock.Rule(columnLeft(block) + (width - w) / 2, w, height, style)
        }
        BlockKind.Table -> block.table?.let { measureTable(it, columnWidth(block)) }
            ?.let { MeasuredBlock.Table(it, columnLeft(block)) } ?: MeasuredBlock.Empty
        BlockKind.FixedPage -> block.fixed?.let { fixed ->
            val scale = min(pageSize.width / fixed.pageWidth, pageSize.height / fixed.pageHeight)
            val w = fixed.pageWidth * scale
            val h = fixed.pageHeight * scale
            MeasuredBlock.Fixed(fixed, (pageSize.width - w) / 2, (pageSize.height - h) / 2, w, h)
        } ?: MeasuredBlock.Empty
    }

    /** Too wide a float, or one leaving too narrow a column beside it, is laid out as a block. */
    private fun measureFloat(block: Block, image: ImageRef, float: BlockFloat): MeasuredBlock.Image? {
        val width = columnWidth(block)
        val (w, h) = fitImage(image, width, pageSize.height.toFloat(), image.columnWidth)
        val gap = maxOf(float.gap, MinFloatGapEm) * basePx
        if (w > width * MaxFloatShare || width - w - gap < MinBesideEm * basePx) return null
        val left = columnLeft(block) + if (float.side == FloatSide.Left) 0f else width - w
        return MeasuredBlock.Image(image, left, w, h, float, gap)
    }

    private class TextColumn(val style: PackTextStyle, val hang: Float, val left: Float, val right: Float)

    private fun textColumn(block: Block): TextColumn {
        val style = styleOf(block)
        val indentPx = indentOf(style) * sizeOf(style) * basePx
        val hang = if (indentPx < 0) min(abs(indentPx), inset(block.insetStart) * basePx) else 0f
        val left = columnLeft(block) - hang
        return TextColumn(style, hang, left, left + columnWidth(block) + hang)
    }

    fun textFrom(block: Block, from: Int): MeasuredBlock.Text = textColumn(block).let { measureText(block, it, it.left, it.right, from) }

    /** The block narrowed to run beside [area]; null when too little of the column is left. */
    fun textBeside(block: Block, area: FloatArea): MeasuredBlock.Text? {
        val column = textColumn(block)
        val (left, right) = area.beside(column.left, column.right)
        return if (right - left < MinBesideEm * basePx) null else measureText(block, column, left, right, 0)
    }

    private fun measureText(block: Block, column: TextColumn, left: Float, right: Float, from: Int): MeasuredBlock.Text {
        val layout = layoutText(block, column.style, right - left, column.hang, from)
        val marker = block.marker?.takeIf { from == 0 }?.let { measureMarker(it, block) }
        val markerLeft = marker?.let { (left - it.size.width - basePx * MarkerGap).coerceAtLeast(0f) } ?: 0f
        return MeasuredBlock.Text(layout, left, marker, markerLeft, from)
    }

    fun layoutText(block: Block, style: PackTextStyle, width: Float, hang: Float = 0f, from: Int = 0): TextLayoutResult {
        val text = buildPackText(
            block.spans, styles, typography,
            dropCap = style.dropcap.takeIf { block.kind == BlockKind.Text && book.decorations },
            smallCapsMissing = ::smallCapsMissing,
        )
        return measurer.measure(
            text = if (from > 0) text.subSequence(from, text.length) else text,
            style = paragraphStyle(block, style, hang, continued = from > 0),
            constraints = Constraints.fixedWidth(width.toInt().coerceAtLeast(1)),
            skipCache = true,
        )
    }

    private fun measureMarker(marker: String, block: Block): TextLayoutResult {
        val markerSpan = Span(marker, block.spans.firstOrNull()?.style ?: block.style)
        return measurer.measure(
            text = buildPackText(listOf(markerSpan), styles, typography, smallCapsMissing = ::smallCapsMissing),
            style = TextStyle(color = theme.textColor, fontFamily = theme.fontFamily),
            softWrap = false,
            skipCache = true,
        )
    }

    private fun paragraphStyle(block: Block, style: PackTextStyle, hang: Float, continued: Boolean): TextStyle {
        val size = sizeOf(style)
        val indentEm = indentOf(style)
        val lineHeight = style.lineHeight?.takeIf { book.spacing } ?: typography.lineHeightMultiplier
        val hangSp = with(density) { hang.toSp() }
        return TextStyle(
            color = theme.textColor,
            fontSize = (theme.fontSize.value * size).sp,
            fontFamily = if (block.kind == BlockKind.Code) FontFamily.Monospace else theme.fontFamily,
            lineHeight = lineHeight.em,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
            textAlign = alignOf(block, style),
            textIndent = when {
                indentEm < 0 -> TextIndent(firstLine = if (continued) hangSp else 0.sp, restLine = hangSp)
                continued -> TextIndent.None
                else -> TextIndent(firstLine = (theme.fontSize.value * size * indentEm).sp)
            },
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
            typography.book.alignment -> book
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
                CellLayout(left, columnWidth * span, measureCell(cell.blocks, cellWidth, pad), cell.borders?.takeIf { book.decorations })
            }
            val height = cells.maxOfOrNull { cell ->
                cell.items.maxOfOrNull { it.dy + itemHeight(it) }?.plus(pad) ?: (2 * pad)
            } ?: (2 * pad)
            RowLayout(row.header, top, height, cells).also { top += height }
        }
        return TableLayout(width, pad, rows, table.collapse)
    }

    private fun measureCell(blocks: List<Block>, width: Float, pad: Float): List<CellItem> {
        var y = pad
        val items = mutableListOf<CellItem>()
        for ((i, block) in blocks.withIndex()) {
            if (i > 0) y += spaceBefore(block)
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

    private fun fitImage(image: ImageRef, maxWidth: Float, maxHeight: Float, share: Float? = null): Pair<Float, Float> {
        val w = image.width?.takeIf { it > 0 }?.toFloat()
        val h = image.height?.takeIf { it > 0 }?.toFloat()
        val target = share?.let { maxWidth * it }
        if (w == null || h == null) {
            val width = target ?: maxWidth
            return width to min(width * FallbackAspect, maxHeight)
        }
        val scale = minOf(target?.let { it / w } ?: density.density, maxWidth / w, maxHeight / h)
        return w * scale to h * scale
    }

    private companion object {
        const val MinColumnFraction = 0.3f
        const val RuleFraction = 0.3f
        const val OwnInsetMaxEm = 3f
        const val MarkerGap = 0.4f
        const val CellPaddingEm = 0.3f
        const val FallbackAspect = 2f / 3f
        const val MaxFloatShare = 0.6f
        const val MinBesideEm = 10f
        const val MinFloatGapEm = 0.5f
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
