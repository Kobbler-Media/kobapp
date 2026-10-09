package dev.kobbler.app.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MovieWantActionTest {

    private fun movie(filePath: String? = null, wantStatus: String? = null) =
        Movie(id = "m1", title = "Obsession", year = 2026, posterUrl = "p", filePath = filePath, wantStatus = wantStatus)

    @Test
    fun streamableMoviesCanStillBeWanted() {
        // Streaming a debrid copy and asking for a download are separate things; a
        // streamable movie is precisely the one worth wanting.
        assertTrue(movie().canBeWanted(canWant = true))
    }

    @Test
    fun aMovieAlreadyInTheLibraryIsNotOffered() {
        assertFalse(movie(filePath = "stream").canBeWanted(canWant = true))
    }

    @Test
    fun viewersAreNotOffered() {
        assertFalse(movie().canBeWanted(canWant = false))
    }

    @Test
    fun anAlreadyWantedMovieIsNotOfferedAgain() {
        assertFalse(movie(wantStatus = "wanted").canBeWanted(canWant = true))
    }
}
