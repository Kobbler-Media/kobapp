package dev.kobbler.app.ui.reader.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextLayoutResult
import dev.kobbler.app.ui.reader.pack.FixedPageDto
import dev.kobbler.app.ui.reader.pack.Frame
import dev.kobbler.app.ui.reader.pack.ImageRef

@Immutable
class PageLayout(val index: Int, val slices: List<PageSlice>, val frames: List<FrameBand>)

@Immutable
data class FrameBand(val frame: Frame, val left: Float, val right: Float, val top: Float, val bottom: Float)

@Immutable
sealed interface PageSlice {
    val blockIndex: Int
    val left: Float
    val top: Float
    val width: Float
    val height: Float
}

val PageSlice.bottom: Float get() = top + height

@Immutable
class TextSlice(
    override val blockIndex: Int,
    val layout: TextLayoutResult,
    val firstLine: Int,
    val lastLine: Int,
    override val left: Float,
    override val top: Float,
    val marker: PlacedMarker? = null,
) : PageSlice {
    val layoutTop: Float = layout.getLineTop(firstLine)
    override val width: Float get() = layout.size.width.toFloat()
    override val height: Float = layout.getLineBottom(lastLine) - layoutTop
    val startOffset: Int get() = layout.getLineStart(firstLine)
    val endOffset: Int get() = layout.getLineEnd(lastLine)
    val isWhole: Boolean get() = firstLine == 0 && lastLine == layout.lineCount - 1
}

@Immutable
class PlacedMarker(val layout: TextLayoutResult, val left: Float, val top: Float)

@Immutable
class ImageSlice(
    override val blockIndex: Int,
    val image: ImageRef,
    override val left: Float,
    override val top: Float,
    override val width: Float,
    override val height: Float,
) : PageSlice

@Immutable
class RuleSlice(
    override val blockIndex: Int,
    override val left: Float,
    override val top: Float,
    override val width: Float,
    override val height: Float,
) : PageSlice

@Immutable
class TableSlice(
    override val blockIndex: Int,
    val table: TableLayout,
    val rows: IntRange,
    override val left: Float,
    override val top: Float,
) : PageSlice {
    val rowsTop: Float = table.rows[rows.first].top
    override val width: Float get() = table.width
    override val height: Float = table.rows[rows.last].let { it.top + it.height } - rowsTop
    val isWhole: Boolean get() = rows.first == 0 && rows.last == table.rows.lastIndex
}

@Immutable
class FixedSlice(
    override val blockIndex: Int,
    val fixed: FixedPageDto,
    override val left: Float,
    override val top: Float,
    override val width: Float,
    override val height: Float,
) : PageSlice
