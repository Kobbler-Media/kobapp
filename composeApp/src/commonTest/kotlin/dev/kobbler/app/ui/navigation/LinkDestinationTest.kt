package dev.kobbler.app.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkDestinationTest {

    @Test
    fun serverLinksResolveToTheirDetailPages() {
        val cases = listOf(
            "/movies/m1" to Destination.MovieDetail("m1"),
            "/tv/s1" to Destination.TvShowDetail("s1"),
            "/tv/episodes/e1" to Destination.TvShowDetail("e1", isEpisodeId = true),
            "/sports/s1" to Destination.TvShowDetail("s1"),
            "/sports/events/e1" to Destination.TvShowDetail("e1", isEpisodeId = true),
            "/library/books/b1" to Destination.BookDetail("b1"),
            "/somewhere/else" to null,
        )
        for ((link, expected) in cases) assertEquals(expected, linkDestination(link), link)
    }

    @Test
    fun autoPlayCarriesIntoVideoDestinations() {
        assertEquals(Destination.MovieDetail("m1", autoPlay = true), linkDestination("/movies/m1", autoPlay = true))
        assertEquals(
            Destination.TvShowDetail("e1", autoPlay = true, isEpisodeId = true),
            linkDestination("/tv/episodes/e1", autoPlay = true),
        )
    }
}
