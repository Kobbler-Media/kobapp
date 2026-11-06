package dev.kobbler.app.ui.reader.layout

import dev.kobbler.app.ui.reader.pack.Clear
import dev.kobbler.app.ui.reader.pack.FloatSide

/**
 * A float placed on the page: text beside it keeps [gap] clear of [left]..[right] until
 * [bottom]. CSS float sides are physical, so they stay put in right-to-left text.
 */
internal class FloatArea(
    val slice: ImageSlice,
    val side: FloatSide,
    val gap: Float,
) {
    val left: Float get() = slice.left
    val right: Float get() = slice.left + slice.width
    val bottom: Float get() = slice.bottom

    fun beside(columnLeft: Float, columnRight: Float): Pair<Float, Float> = when (side) {
        FloatSide.Left -> maxOf(columnLeft, right + gap) to columnRight
        FloatSide.Right -> columnLeft to minOf(columnRight, left - gap)
    }

    fun clearedBy(clear: Clear?): Boolean = when (clear) {
        null -> false
        Clear.Both -> true
        Clear.Left -> side == FloatSide.Left
        Clear.Right -> side == FloatSide.Right
    }
}
