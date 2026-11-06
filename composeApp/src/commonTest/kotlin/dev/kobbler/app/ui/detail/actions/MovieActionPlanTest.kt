package dev.kobbler.app.ui.detail.actions

import dev.kobbler.app.data.Movie
import dev.kobbler.app.ui.detail.actions.MovieAction.Download
import dev.kobbler.app.ui.detail.actions.MovieAction.FromStart
import dev.kobbler.app.ui.detail.actions.MovieAction.Keep
import dev.kobbler.app.ui.detail.actions.MovieAction.Kids
import dev.kobbler.app.ui.detail.actions.MovieAction.Play
import dev.kobbler.app.ui.detail.actions.MovieAction.Resume
import dev.kobbler.app.ui.detail.actions.MovieAction.SetWatched
import dev.kobbler.app.ui.detail.actions.MovieAction.Streams
import dev.kobbler.app.ui.detail.actions.MovieAction.Want
import dev.kobbler.app.ui.detail.actions.MovieAction.WatchAgain
import dev.kobbler.app.ui.detail.actions.MovieAction.Watchlist
import kotlin.test.Test
import kotlin.test.assertEquals

class MovieActionPlanTest {

    private val movie = Movie(id = "m", title = "Movie", year = 2026, posterUrl = "", filePath = "/m.mkv", runtimeMinutes = 100)
    private val online = MovieActionState(movie = movie, canDownload = true, canWant = true)

    private data class Case(val name: String, val state: MovieActionState, val expected: List<MovieAction>)

    private val cases = listOf(
        Case("unstarted plays", online, listOf(Play, Download, SetWatched(true), Watchlist)),
        Case(
            "in progress resumes, then restarts",
            online.copy(movie = movie.copy(watchPositionSeconds = 600)),
            listOf(Resume(600), FromStart, Download, SetWatched(true), Watchlist),
        ),
        Case(
            "watched is watched again",
            online.copy(movie = movie.copy(isWatched = true, watchPositionSeconds = 5_900)),
            listOf(WatchAgain, Download, SetWatched(false), Watchlist),
        ),
        Case(
            "no file and no stream can only be wanted",
            online.copy(movie = movie.copy(filePath = null)),
            listOf(Want, Watchlist),
        ),
        Case(
            "no file but a stream plays it and offers the picker",
            online.copy(movie = movie.copy(filePath = null), canStream = true, canPickStream = true),
            listOf(Play, Streams, Want, SetWatched(true), Watchlist),
        ),
        Case("not downloadable offers no download", online.copy(canDownload = false), listOf(Play, SetWatched(true), Watchlist)),
        Case(
            "no video playback offers nothing to play or download",
            online.copy(canPlayVideo = false),
            listOf(Watchlist),
        ),
        Case(
            "offline with the download plays it and manages the download",
            online.copy(offline = true, playableOffline = true, downloadStarted = true),
            listOf(Play, Download),
        ),
        Case(
            "offline mid-download only shows the download",
            online.copy(offline = true, downloadStarted = true),
            listOf(Download),
        ),
        Case("offline without a download offers nothing", online.copy(offline = true), emptyList()),
        Case(
            "keep and kids access come last",
            online.copy(canKeep = true, canManageKids = true),
            listOf(Play, Download, SetWatched(true), Watchlist, Keep, Kids),
        ),
    )

    @Test
    fun `actions per state`() {
        for (case in cases) assertEquals(case.expected, movieActions(case.state), case.name)
    }
}
