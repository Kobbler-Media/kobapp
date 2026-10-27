package dev.kobbler.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.kobbler.kobpalette.ColorPalette
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtToneTest {
    private val bg = Color(0xFF0B0D10)
    private val accent = Color(0xFF4F8BFF)

    @Test
    fun `the tone comes from the art's dark swatch, sunk into the page`() {
        val swatch = Color(0xFF6A2E1C)
        assertEquals(lerp(bg, swatch, ArtToneStrength), artTone(ColorPalette(darkMuted = swatch), bg, accent))
    }

    @Test
    fun `a dark vibrant swatch beats a muted one`() {
        val vibrant = Color(0xFF7A1010)
        val palette = ColorPalette(darkVibrant = vibrant, darkMuted = Color(0xFF333333))
        assertEquals(lerp(bg, vibrant, ArtToneStrength), artTone(palette, bg, accent))
    }

    @Test
    fun `before the palette lands the tone is the section accent, never stripes`() {
        assertEquals(lerp(bg, accent, ArtToneStrength), artTone(ColorPalette(), bg, accent))
    }
}
