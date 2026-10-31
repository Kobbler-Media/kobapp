package dev.kobbler.kobplayer.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

internal fun WindowInsets.mirrored(): WindowInsets = MirroredInsets(this)

private class MirroredInsets(private val insets: WindowInsets) : WindowInsets {
    private fun horizontal(density: Density, direction: LayoutDirection) =
        maxOf(insets.getLeft(density, direction), insets.getRight(density, direction))

    private fun vertical(density: Density) = maxOf(insets.getTop(density), insets.getBottom(density))

    override fun getLeft(density: Density, layoutDirection: LayoutDirection) = horizontal(density, layoutDirection)
    override fun getRight(density: Density, layoutDirection: LayoutDirection) = horizontal(density, layoutDirection)
    override fun getTop(density: Density) = vertical(density)
    override fun getBottom(density: Density) = vertical(density)

    override fun toString() = "mirrored($insets)"
}
