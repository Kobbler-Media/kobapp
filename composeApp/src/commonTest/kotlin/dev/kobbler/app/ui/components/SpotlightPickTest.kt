package dev.kobbler.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class SpotlightPickTest {
    private val posterOnly = Spotlight(posterUrl = "p", title = "Poster only")
    private val first = Spotlight(backdropUrl = "b1", title = "First")
    private val second = Spotlight(backdropUrl = "b2", title = "Second")
    private val bare = Spotlight(title = "No art")

    @Test
    fun aRealBackdropBeatsAPosterAndTheRowsOrderBreaksTies() {
        assertEquals(first, pickSpotlight(listOf(bare, posterOnly, first, second), current = null))
    }

    @Test
    fun whatIsAlreadyShowingIsPassedOver() {
        assertEquals(second, pickSpotlight(listOf(first, second), current = first))
    }

    @Test
    fun aRowOfOnlyTheCurrentPictureKeepsIt() {
        assertEquals(first, pickSpotlight(listOf(first), current = first))
    }

    @Test
    fun postersStandInWhenNothingHasABackdrop() {
        assertEquals(posterOnly, pickSpotlight(listOf(bare, posterOnly), current = null))
    }

    @Test
    fun nothingWithArtPicksNothing() {
        assertEquals(null, pickSpotlight(listOf(bare), current = null))
    }
}
