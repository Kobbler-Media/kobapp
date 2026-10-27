package dev.kobbler.app.ui.detail

import dev.kobbler.app.theme.KobappColorsDefault
import dev.kobbler.app.theme.ratingColor
import kotlin.test.Test
import kotlin.test.assertEquals

class RatingScaleTest {

    @Test
    fun `books rate out of five, everything else out of ten`() {
        assertEquals(5, ratingOutOf("book"))
        assertEquals(10, ratingOutOf("movie"))
        assertEquals(10, ratingOutOf("tv"))
    }

    @Test
    fun `colour bands follow the share of the scale, not the raw number`() {
        val c = KobappColorsDefault
        listOf(
            Triple(7f, 10, c.ok),
            Triple(6f, 10, c.warn),
            Triple(3.5f, 10, c.err),
            Triple(3.5f, 5, c.ok),
            Triple(2.5f, 5, c.warn),
            Triple(2f, 5, c.err),
        ).forEach { (rating, outOf, expected) ->
            assertEquals(expected, ratingColor(rating, outOf, c), "$rating / $outOf")
        }
    }
}
