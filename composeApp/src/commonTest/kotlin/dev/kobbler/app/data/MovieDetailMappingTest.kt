package dev.kobbler.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MovieDetailMappingTest {

    private val base = "https://kob.example"

    private fun dto(
        wantStatus: String = "",
        isInLibrary: Boolean = false,
    ) = MovieDetailDto(
        id = "m1",
        title = "Obsession",
        year = 2026,
        runtime = 109,
        description = "A hopeless romantic gets exactly what he asked for.",
        tagline = "Be careful what you wish for.",
        contentRating = "R",
        rating = 4.1,
        isInLibrary = isInLibrary,
        wantStatus = wantStatus,
        cast = listOf(CastDto(personName = "Ana Ruiz", characterName = "Willow")),
        crew = listOf(
            CrewDto(personName = "Dana Vega", department = "Directing", job = "Director"),
            CrewDto(personName = "Sam Okoro", department = "Writing", job = "Screenplay"),
            CrewDto(personName = "Lee Park", department = "Sound", job = "Boom Operator"),
        ),
    )

    @Test
    fun taglineAndCertificationSurvive() {
        val movie = dto().toMovie(base)
        assertEquals("Be careful what you wish for.", movie.tagline)
        assertEquals("R", movie.contentRating)
    }

    @Test
    fun directorsAndWritersAreLifted() {
        val movie = dto().toMovie(base)
        assertEquals(listOf("Dana Vega"), movie.directors)
        assertEquals(listOf("Sam Okoro"), movie.writers)
    }

    @Test
    fun unrelatedCrewIsNotShown() {
        val movie = dto().toMovie(base)
        assertFalse(movie.directors.contains("Lee Park"))
        assertFalse(movie.writers.contains("Lee Park"))
    }

    @Test
    fun showsCarryTheirCertification() {
        // Shows only ever return Executive Producer crew, so there is no director or
        // writer line to lift; the certification is the part worth having.
        val show = ShowDetailDto(id = "s1", title = "Ted Lasso", contentRating = "TV-MA").toTvShow(base)
        assertEquals("TV-MA", show.contentRating)
    }

    @Test
    fun showsCarryTheirGenres() {
        val show = ShowDetailDto(id = "s1", title = "Ted Lasso", genres = listOf("Comedy", "Drama")).toTvShow(base)
        assertEquals(listOf("Comedy", "Drama"), show.genres)
    }

    @Test
    fun wantStatusReachesTheModel() {
        assertTrue(dto(wantStatus = "wanted").toMovie(base).isWanted)
        assertFalse(dto(wantStatus = "").toMovie(base).isWanted)
        assertFalse(dto(wantStatus = "none").toMovie(base).isWanted)
    }
}
