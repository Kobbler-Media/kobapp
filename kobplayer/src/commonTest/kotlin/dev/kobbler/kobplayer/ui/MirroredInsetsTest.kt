package dev.kobbler.kobplayer.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class MirroredInsetsTest {

    private val density = Density(1f)

    private fun WindowInsets.sides() = listOf(
        getLeft(density, LayoutDirection.Ltr),
        getTop(density),
        getRight(density, LayoutDirection.Ltr),
        getBottom(density),
    )

    @Test
    fun `a cutout on one edge pads the opposite edge to match`() {
        val table = listOf(
            WindowInsets(top = 40) to listOf(0, 40, 0, 40),
            WindowInsets(left = 32, bottom = 24) to listOf(32, 24, 32, 24),
            WindowInsets(left = 10, top = 5, right = 30, bottom = 20) to listOf(30, 20, 30, 20),
            WindowInsets(0, 0, 0, 0) to listOf(0, 0, 0, 0),
        )
        for ((insets, expected) in table) assertEquals(expected, insets.mirrored().sides(), "$insets")
    }
}
