package dev.kobbler.app.ui.player

import dev.kobbler.app.data.ContinueEpisode
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/**
 * A detail page behind the player has to show what was just watched.
 *
 * Its copy of the title was fetched when the page opened and is never refetched on the
 * way back, so backing out of an hour of a film left the page still offering to resume
 * from wherever the server had been asked about last — the one number on screen that the
 * viewer can check against what they just did.
 */
class ReportedProgressTest {

    private val movie = Movie(
        id = "m1", title = "1408", year = 2007, posterUrl = "",
        watchPositionSeconds = 57,
    )

    @Test
    fun `a movie takes the position this session reported`() {
        assertEquals(4042, movie.withReportedProgress(mapOf("m1" to WatchedPoint(4042))).watchPositionSeconds)
    }

    @Test
    fun `a movie nothing was reported for is untouched`() {
        assertEquals(57, movie.withReportedProgress(mapOf("m2" to WatchedPoint(4042))).watchPositionSeconds)
        assertEquals(57, movie.withReportedProgress(emptyMap()).watchPositionSeconds)
    }

    private val show = TvShow(
        id = "s1", title = "Show", year = 2020, posterUrl = "",
        seasons = listOf(
            Season(
                number = 1,
                episodes = listOf(
                    Episode(id = "e1", number = 1, title = "One", runtimeMinutes = 60, watchProgress = 0.1f),
                    Episode(id = "e2", number = 2, title = "Two", runtimeMinutes = 60),
                ),
            ),
        ),
    )

    private fun episode(show: TvShow, id: String) =
        show.seasons.first().episodes.first { it.id == id }

    @Test
    fun `an episode takes the position this session reported, as a fraction of its runtime`() {
        val updated = show.withReportedProgress(mapOf("e1" to WatchedPoint(1800)))
        assertEquals(0.5f, episode(updated, "e1").watchProgress)
    }

    @Test
    fun `an episode nothing was reported for is untouched`() {
        val updated = show.withReportedProgress(mapOf("e1" to WatchedPoint(1800)))
        assertEquals(0.1f, show.seasons.first().episodes.first().watchProgress, "the original was mutated")
        assertNull(episode(updated, "e2").watchProgress)
    }

    @Test
    fun `an episode watched when fetched is unwatched again once this session saved it short of the end or unmarked it`() {
        val watched = show.copy(seasons = show.seasons.map { s -> s.copy(episodes = s.episodes.map { it.copy(isWatched = true, watchProgress = null) }) })
        for ((name, point) in listOf("rewatched part-way" to WatchedPoint(1800), "marked unwatched" to WatchedPoint(0))) {
            val e1 = episode(watched.withReportedProgress(mapOf("e1" to point)), "e1")
            assertEquals(false, e1.isWatched, name)
            assertEquals(if (point.seconds > 0) 0.5f else null, e1.watchProgress, name)
        }
    }

    /** Without a runtime there is no fraction to draw; the reported seconds say nothing. */
    @Test
    fun `an episode with no runtime keeps what it had`() {
        val noRuntime = TvShow(
            id = "s1", title = "Show", year = 2020, posterUrl = "",
            seasons = listOf(
                Season(number = 1, episodes = listOf(Episode(id = "e1", number = 1, title = "One"))),
            ),
        )
        assertNull(episode(noRuntime.withReportedProgress(mapOf("e1" to WatchedPoint(1800))), "e1").watchProgress)
    }

    @Test
    fun `a film played to the end reads as watched, not as somewhere to resume`() {
        val watched = movie.withReportedProgress(mapOf("m1" to WatchedPoint(6746, finished = true)))
        assertTrue(watched.isWatched)
        assertNull(watched.watchPositionSeconds)
    }

    @Test
    fun `an episode played to the end reads as watched`() {
        val updated = show.withReportedProgress(mapOf("e1" to WatchedPoint(3600, finished = true)))
        assertTrue(episode(updated, "e1").isWatched)
        assertNull(episode(updated, "e1").watchProgress)
    }

    @Test
    fun `a position past the end does not overflow the bar`() {
        val updated = show.withReportedProgress(mapOf("e1" to WatchedPoint(99_999)))
        assertEquals(1f, episode(updated, "e1").watchProgress)
    }

    private val today = LocalDate(2026, 9, 26)

    private fun binge(vararg episodes: Episode, cont: String) = TvShow(
        id = "s1", title = "Show", year = 2020, posterUrl = "",
        seasons = listOf(Season(number = 1, episodes = episodes.toList())),
        continueEpisode = ContinueEpisode(0, episodes.indexOfFirst { it.id == cont }, cont, positionSeconds = 120),
    )

    private fun owned(id: String, n: Int) = Episode(id = id, number = n, title = id, runtimeMinutes = 60, filePath = "stream")

    /**
     * Finishing the episode Resume named left it naming that episode, and the player it
     * handed off to was the finished one — pressing it played the last second again.
     */
    @Test
    fun `finishing the continue episode moves Resume on to the next one`() {
        val show = binge(owned("e1", 1), owned("e2", 2), owned("e3", 3), cont = "e1")
        val cont = show.withReportedProgress(mapOf("e1" to WatchedPoint(3600, finished = true)), today).continueEpisode!!
        assertEquals("e2", cont.episodeId)
        assertEquals(1, cont.episodeIdx)
        assertEquals(0, cont.positionSeconds)
    }

    @Test
    fun `finishing a later episode than the continue one moves Resume past it`() {
        val show = binge(owned("e1", 1), owned("e2", 2), owned("e3", 3), cont = "e1")
        val cont = show.withReportedProgress(mapOf("e2" to WatchedPoint(3600, finished = true)), today).continueEpisode!!
        assertEquals("e3", cont.episodeId)
    }

    @Test
    fun `the next pick skips watched episodes and ones nothing can play`() {
        val show = binge(
            owned("e1", 1),
            owned("e2", 2).copy(isWatched = true),
            Episode(id = "e3", number = 3, title = "wanted", wantStatus = "wanted", airDate = LocalDate(2026, 9, 1)),
            Episode(id = "e4", number = 4, title = "unaired", wantStatus = "none", airDate = LocalDate(2026, 10, 1)),
            Episode(id = "e5", number = 5, title = "streamable", wantStatus = "none", airDate = LocalDate(2026, 9, 20)),
            cont = "e1",
        )
        val cont = show.withReportedProgress(mapOf("e1" to WatchedPoint(3600, finished = true)), today).continueEpisode!!
        assertEquals("e5", cont.episodeId)
    }

    @Test
    fun `finishing the last playable episode leaves nothing to resume`() {
        val show = binge(owned("e1", 1), owned("e2", 2), cont = "e2")
        assertNull(show.withReportedProgress(mapOf("e2" to WatchedPoint(3600, finished = true)), today).continueEpisode)
    }

    @Test
    fun `a part-watched continue episode resumes from where this session left it`() {
        val show = binge(owned("e1", 1), owned("e2", 2), cont = "e1")
        val cont = show.withReportedProgress(mapOf("e1" to WatchedPoint(1800)), today).continueEpisode!!
        assertEquals("e1", cont.episodeId)
        assertEquals(1800, cont.positionSeconds)
    }

    @Test
    fun `finishing an earlier episode does not drag Resume back`() {
        val show = binge(owned("e1", 1), owned("e2", 2), owned("e3", 3), cont = "e3")
        assertEquals("e3", show.withReportedProgress(mapOf("e1" to WatchedPoint(3600, finished = true)), today).continueEpisode!!.episodeId)
    }
}
