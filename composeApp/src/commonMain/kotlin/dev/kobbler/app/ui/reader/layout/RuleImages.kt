package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.geometry.Rect
import dev.kobbler.app.ui.reader.pack.RuleImage
import kotlin.math.ceil

internal val RuleImage.aspect: Float
    get() {
        val w = width?.takeIf { it > 0 } ?: return 1f
        val h = height?.takeIf { it > 0 } ?: return 1f
        return w.toFloat() / h
    }

/** Where a rule's ornament is drawn: once filling the rule, or tiled from its start across it. */
internal fun RuleSlice.imageTiles(): List<Rect> {
    val image = style?.image ?: return emptyList()
    if (!image.repeat) return listOf(Rect(left, top, left + width, top + height))
    val tile = height * image.aspect
    if (tile <= 0f) return emptyList()
    return List(ceil(width / tile).toInt()) { Rect(left + it * tile, top, left + (it + 1) * tile, top + height) }
}
