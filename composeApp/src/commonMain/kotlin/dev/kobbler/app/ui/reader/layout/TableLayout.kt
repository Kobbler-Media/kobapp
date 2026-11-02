package dev.kobbler.app.ui.reader.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextLayoutResult
import dev.kobbler.app.ui.reader.pack.ImageRef

@Immutable
class TableLayout(val width: Float, val cellPadding: Float, val rows: List<RowLayout>)

@Immutable
class RowLayout(val header: Boolean, val top: Float, val height: Float, val cells: List<CellLayout>)

@Immutable
class CellLayout(val left: Float, val width: Float, val items: List<CellItem>)

@Immutable
sealed interface CellItem {
    val dx: Float
    val dy: Float

    class Text(val layout: TextLayoutResult, override val dx: Float, override val dy: Float) : CellItem

    class Image(
        val image: ImageRef,
        override val dx: Float,
        override val dy: Float,
        val width: Float,
        val height: Float,
    ) : CellItem
}

internal val TableLayout.totalHeight: Float get() = rows.lastOrNull()?.let { it.top + it.height } ?: 0f
