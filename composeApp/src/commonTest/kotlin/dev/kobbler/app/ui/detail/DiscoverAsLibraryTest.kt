package dev.kobbler.app.ui.detail

import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.DiscoverEpisode
import dev.kobbler.app.data.DiscoverEpisodeRef
import dev.kobbler.app.data.DiscoverSeason
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscoverAsLibraryTest {

    private fun season(n: Int, count: Int? = 8) = DiscoverSeason(seasonNumber = n, title = "Season $n", episodeCount = count, airDate = null)

    private fun detail(vararg seasons: DiscoverSeason) = DiscoverDetail(
        mediaType = "tv", provider = "tmdb", title = "Show", year = 2021,
        description = "About", posterUrl = "p", backdropUrl = "b", genres = emptyList(),
        rating = 7.5f, cast = emptyList(), network = "HBO", runtime = 50,
        authors = emptyList(), libraryId = null, wantStatus = null, isInLibrary = false,
        identifiers = emptyMap(), providerId = "1", seasons = seasons.toList(),
    )

    @Test
    fun `the stream target is the first episode outside the specials`() {
        val cases = listOf(
            detail(season(0), season(1), season(2)) to DiscoverEpisodeRef(1, 1),
            detail(season(2), season(1)) to DiscoverEpisodeRef(1, 1),
            detail(season(0)) to DiscoverEpisodeRef(0, 1),
            detail(season(0), season(1, count = 0), season(2)) to DiscoverEpisodeRef(2, 1),
        )
        for ((d, expected) in cases) assertEquals(expected, d.streamTarget(emptyMap()), "${d.seasons}")
        assertNull(detail().streamTarget(emptyMap()))
    }

    @Test
    fun `a loaded season starts the stream at its first listed episode`() {
        val eps = mapOf(1 to listOf(ep(2), ep(3)))
        assertEquals(DiscoverEpisodeRef(1, 2), detail(season(1)).streamTarget(eps))
    }

    @Test
    fun `a discover show reads as a library show, specials left out, episodes as loaded`() {
        val show = detail(season(0), season(2), season(1)).asTvShow(
            mapOf(1 to listOf(ep(1, title = null, still = "s1", runtime = 65, air = "2021-03-04", synopsis = "Pilot"))),
        )
        assertEquals(listOf(1, 2), show.seasons.map { it.number })
        assertEquals(emptyList(), show.seasons[1].episodes)
        val e = show.seasons[0].episodes.single()
        assertEquals("Episode 1", e.title)
        assertEquals("s1", e.thumbnailUrl)
        assertEquals("1h 5m", e.duration)
        assertEquals(65, e.runtimeMinutes)
        assertEquals(LocalDate(2021, 3, 4), e.airDate)
        assertEquals("Pilot", e.synopsis)
        assertNull(e.filePath)
        assertNull(e.id)
        assertEquals(Triple("Show", 2021, "HBO"), Triple(show.title, show.year, show.network))
        assertEquals("About", show.synopsis)
    }

    @Test
    fun `the episode count comes from the season list, not the episodes loaded so far`() {
        assertEquals(18, detail(season(0, 3), season(1, 8), season(2, 10), season(3, null)).episodeTotal())
    }

    @Test
    fun `an episode is tagged as the library tags it`() {
        assertEquals("S01 E03", episodeTag(1, 3))
        assertEquals("S12 E110", episodeTag(12, 110))
    }

    @Test
    fun `a discover movie reads as a library movie`() {
        val m = detail().copy(mediaType = "movie", runtime = 125, genres = listOf("Drama", "War")).asMovie()
        assertEquals(Triple("Show", 2021, "2h 5m"), Triple(m.title, m.year, m.duration))
        assertEquals(125, m.runtimeMinutes)
        assertEquals(listOf("Drama", "War"), m.genres)
        assertEquals(Pair("About", "b"), Pair(m.synopsis, m.backdropUrl))
        assertNull(m.filePath)
    }

    private fun ep(n: Int, title: String? = "E$n", still: String? = null, runtime: Int? = null, air: String? = null, synopsis: String? = null) =
        DiscoverEpisode(episodeNumber = n, title = title, airDate = air, runtime = runtime, stillUrl = still, description = synopsis)
}
