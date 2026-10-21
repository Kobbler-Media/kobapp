package dev.kobbler.app.ui.player

import dev.kobbler.app.data.ContinueEpisode
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.DiscoverEpisodeRef
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.StreamTarget
import dev.kobbler.app.data.TvShow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstantTargetTest {

    @Test
    fun `a movie with a file is never warmed`() {
        val movie = Movie(id = "m1", title = "M", year = 2020, posterUrl = "")
        assertEquals(StreamTarget.movie("m1"), movie.instantTarget())
        assertNull(movie.copy(filePath = "/m.mkv").instantTarget())
    }

    @Test
    fun `a show warms the episode its resume would stream, and only when that has no file`() {
        fun show(file: String?) = TvShow(
            id = "s1", title = "S", year = 2020, posterUrl = "",
            seasons = listOf(Season(number = 1, episodes = listOf(Episode(id = "e1", number = 1, title = "One", filePath = file)))),
            continueEpisode = ContinueEpisode(seasonIdx = 0, episodeIdx = 0, episodeId = "e1"),
        )
        assertEquals(StreamTarget.episode("e1"), show(null).instantTarget())
        assertNull(show("/e1.mkv").instantTarget())
        assertNull(show(null).copy(continueEpisode = null).instantTarget())
    }

    @Test
    fun `a discover warm and the press that claims it agree on the title`() {
        val detail = DiscoverDetail(
            mediaType = "tv", provider = "tmdb", title = "T", year = null, description = null, posterUrl = "",
            backdropUrl = null, genres = emptyList(), rating = null, cast = emptyList(), network = null, runtime = null,
            authors = emptyList(), libraryId = null, wantStatus = null, isInLibrary = false, identifiers = emptyMap(),
            providerId = "1",
        )
        val ep = DiscoverEpisodeRef(1, 2)
        assertEquals(StreamTarget.discover(detail, ep).key, StreamTarget.discover(detail.copy(rating = 8f), DiscoverEpisodeRef(1, 2)).key)
        assertTrue(StreamTarget.discover(detail, ep).key != StreamTarget.discover(detail, DiscoverEpisodeRef(1, 3)).key)
    }

    @Test
    fun `only a next episode with no file is warmed for what is playing`() {
        fun show(vararg files: String?) = TvShow(
            id = "s1", title = "S", year = 2020, posterUrl = "",
            seasons = listOf(
                Season(number = 1, episodes = listOf(Episode(id = "e1", number = 1, title = "One", filePath = files[0]))),
                Season(number = 2, episodes = files.drop(1).mapIndexed { i, f -> Episode(id = "e${i + 2}", number = i + 1, title = "Ep", filePath = f) }),
            ),
        )
        data class Case(val name: String, val show: TvShow, val playing: String?, val warmed: StreamTarget?)
        listOf(
            Case("next has no file", show(null, null), "e1", StreamTarget.episode("e2")),
            Case("next has a file", show(null, "/e2.mkv"), "e1", null),
            Case("across a season", show("/e1.mkv", null, "/e3.mkv"), "e1", StreamTarget.episode("e2")),
            Case("last episode", show(null, null), "e2", null),
            Case("nothing playing", show(null, null), null, null),
            Case("not this show", show(null, null), "x", null),
        ).forEach { assertEquals(it.warmed, nextEpisodeWarmTarget(it.show, it.playing), it.name) }
    }

    @Test
    fun `the next episode is due at seventy percent`() {
        assertFalse(nextEpisodeDue(0, 0))
        assertFalse(nextEpisodeDue(2_519_000, 3_600_000))
        assertTrue(nextEpisodeDue(2_520_000, 3_600_000))
    }
}
