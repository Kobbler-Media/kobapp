package dev.kobbler.app.ui.detail

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.ContinueEpisode
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.ui.player.FakePlayer
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.InlineMoviePlayer
import dev.kobbler.app.ui.player.InlineTvPlayer
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.app.ui.player.startPlayback
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SESSIONS = "/api/v1/admin/playback/sessions"
private const val MOVIE_STATE = "/api/v1/admin/movies/m1/watch-state"
private const val EPISODE_STATE = "/api/v1/admin/tv/episodes/ep-1/watch-state"

private val nearEnd = Movie(
    id = "m1", title = "Movie", year = 2020, posterUrl = "",
    filePath = "/library/movie.mkv", runtimeMinutes = 100, watchPositionSeconds = 5_900,
)

private val showNearEnd = TvShow(
    id = "show-1", title = "Show", year = 2020, posterUrl = "",
    seasons = listOf(
        Season(number = 1, episodes = listOf(Episode(id = "ep-1", number = 1, title = "One", filePath = "/e1.mkv"))),
    ),
    continueEpisode = ContinueEpisode(seasonIdx = 0, episodeIdx = 0, episodeId = "ep-1", positionSeconds = 2_500),
)

private val sessionBody = """{"session_id":"s1","direct_url":"/api/v1/admin/stream/s1/play","method":"direct"}"""

private fun routes(watchState: String, statePath: String = MOVIE_STATE) = mapOf<String, (HttpExchange) -> String>(
    SESSIONS to { sessionBody },
    statePath to { ex -> if (ex.requestMethod == "GET") watchState else "{}" },
)

/**
 * A title stopped in its credits kept offering Resume, and that was the only way in —
 * getting back to the opening meant scrubbing the whole runtime with a remote.
 */
@OptIn(ExperimentalTestApi::class)
class PlayFromStartTest {

    private class Rig(val api: KobblerApi, val gps: GlobalPlayerState, val stub: StubServer)

    private fun ComposeUiTest.movieRig(movie: Movie, watchState: String, body: ComposeUiTest.(Rig) -> Unit) =
        StubServer(routes(watchState)).use { stub ->
            val rig = Rig(KobblerApi(HttpClient(), stub.url, "token"), GlobalPlayerState(), stub)
            setContent {
                InlineMoviePlayer(
                    movie = movie, api = rig.api, formFactor = FormFactor.TV,
                    player = FakePlayer().also { it.durationMs.value = 6_000_000 }, gps = rig.gps, onBack = {},
                )
            }
            waitForIdle()
            body(rig)
        }

    private fun ComposeUiTest.showRig(show: TvShow, watchState: String, body: ComposeUiTest.(Rig) -> Unit) =
        StubServer(routes(watchState, EPISODE_STATE)).use { stub ->
            val rig = Rig(KobblerApi(HttpClient(), stub.url, "token"), GlobalPlayerState(), stub)
            setContent {
                InlineTvPlayer(
                    show = show, api = rig.api, formFactor = FormFactor.TV,
                    player = FakePlayer().also { it.durationMs.value = 3_000_000 }, gps = rig.gps, onBack = {},
                )
            }
            waitForIdle()
            body(rig)
        }

    private fun ComposeUiTest.awaitLoaded(gps: GlobalPlayerState, id: String) =
        waitUntil(timeoutMillis = 20_000) { gps.mediaId == id && gps.isFullscreen }

    private fun StubServer.lastSessionStart(): String? =
        Regex("\"start_time\":(\\d+)").find(requestBodies[SESSIONS].orEmpty())?.groupValues?.get(1)

    @Test
    fun movieFromStartIgnoresTheSavedPosition() = runComposeUiTest {
        movieRig(nearEnd, """{"watched":false,"position_seconds":5900}""") { rig ->
            onNodeWithText("Resume").assertExists()
            onNodeWithContentDescription("From start").performClick()
            awaitLoaded(rig.gps, "m1")
            assertEquals(0L, rig.gps.pendingSeekMs ?: 0L)
            assertEquals(null, rig.stub.lastSessionStart(), "the session was rebased to the old position")
        }
    }

    @Test
    fun debridMovieFromStartIgnoresTheSavedPosition() = runComposeUiTest {
        val stub = StubServer(routes("""{"watched":false,"position_seconds":5900}""") + mapOf(
            "/api/v1/admin/stream/search" to { _: HttpExchange ->
                """{"options":[{"id":"o1","release_title":"Movie.2020","debrid_provider":"realdebrid",
                    "debrid_provider_id":"p1","cached":true}],"errors":[]}"""
            },
            "/api/v1/admin/stream/resolve" to { _: HttpExchange ->
                """{"session_id":"sess-1","method":"DirectPlay","direct_url":"/api/v1/admin/stream/sess-1/play",
                    "duration_seconds":6000,"subtitles":[],"audio_tracks":[]}"""
            },
        ))
        stub.use {
            val api = KobblerApi(HttpClient(), stub.url, "token").apply { canStream = true }
            val gps = GlobalPlayerState()
            setContent {
                InlineMoviePlayer(
                    movie = nearEnd.copy(filePath = null), api = api, formFactor = FormFactor.TV,
                    player = FakePlayer().also { it.durationMs.value = 6_000_000 }, gps = gps, onBack = {},
                )
            }
            waitForIdle()
            onNodeWithContentDescription("From start").performClick()
            awaitLoaded(gps, "m1")
            assertEquals(0L, gps.pendingSeekMs ?: 0L)
            val resolve = stub.requestBodies.getValue("/api/v1/admin/stream/resolve")
            assertFalse(resolve.contains("start_time"), "the stream was resolved at the old position: $resolve")
        }
    }

    @Test
    fun movieResumeStillResumes() = runComposeUiTest {
        movieRig(nearEnd, """{"watched":false,"position_seconds":5900}""") { rig ->
            onNodeWithText("Resume").performClick()
            awaitLoaded(rig.gps, "m1")
            assertEquals(5_900_000L, rig.gps.pendingSeekMs)
        }
    }

    @Test
    fun aWatchedMoviePlaysFromTheStart() = runComposeUiTest {
        val watched = nearEnd.copy(isWatched = true)
        movieRig(watched, """{"watched":true,"position_seconds":5900}""") { rig ->
            onAllNodesWithText("Resume").assertCountEquals(0)
            onAllNodesWithContentDescription("From start").assertCountEquals(0)
            onNodeWithText("Play").performClick()
            awaitLoaded(rig.gps, "m1")
            assertEquals(0L, rig.gps.pendingSeekMs ?: 0L)
            assertEquals(null, rig.stub.lastSessionStart())
        }
    }

    @Test
    fun markingAMovieUnwatchedForgetsWhereItWasLeft() = runComposeUiTest {
        val watched = nearEnd.copy(isWatched = true)
        movieRig(watched, """{"watched":true,"position_seconds":5900}""") { rig ->
            onNodeWithContentDescription("Watched").performClick()
            waitUntil(timeoutMillis = 5_000) { rig.stub.requestBodies[MOVIE_STATE].orEmpty().contains("watched") }
            val sent = rig.stub.requestBodies.getValue(MOVIE_STATE)
            assertTrue(sent.contains("\"watched\":false"), sent)
            assertTrue(sent.contains("\"position_seconds\":0"), "unwatching kept the old position: $sent")
            onNodeWithContentDescription("Mark watched").assertExists()
            onNodeWithText("Play").assertExists()
            onAllNodesWithText("Resume").assertCountEquals(0)
        }
    }

    @Test
    fun episodeFromStartIgnoresTheSavedPosition() = runComposeUiTest {
        showRig(showNearEnd, """{"watched":false,"position_seconds":2500}""") { rig ->
            onNodeWithContentDescription("From start").performClick()
            awaitLoaded(rig.gps, "ep-1")
            assertEquals(0L, rig.gps.pendingSeekMs ?: 0L)
            assertEquals(null, rig.stub.lastSessionStart())
        }
    }

    @Test
    fun episodeResumeStillResumes() = runComposeUiTest {
        showRig(showNearEnd, """{"watched":false,"position_seconds":2500}""") { rig ->
            onNodeWithText("Resume", substring = true).performClick()
            awaitLoaded(rig.gps, "ep-1")
            assertEquals(2_500_000L, rig.gps.pendingSeekMs)
        }
    }

    @Test
    fun markingAnEpisodeUnwatchedForgetsWhereItWasLeft() {
        StubServer(routes("{}", EPISODE_STATE)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            runBlocking { api.setEpisodeWatched("ep-1", false) }
            val sent = stub.requestBodies.getValue(EPISODE_STATE)
            assertTrue(sent.contains("\"position_seconds\":0"), "unwatching kept the old position: $sent")
        }
    }

    /** Every play path reads the saved position through here, so a finished title restarts everywhere. */
    @Test
    fun aFinishedTitleHasNothingToResume() {
        for ((type, path) in listOf("movie" to MOVIE_STATE, "episode" to EPISODE_STATE)) {
            StubServer(routes("""{"watched":true,"position_seconds":2500}""", path)).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                val id = if (type == "movie") "m1" else "ep-1"
                val start = runBlocking { startPlayback(api, type, id) }
                assertEquals(0, start?.seekToSec, "$type resumed at its credits")
                assertFalse(stub.lastSessionStart() != null, "$type session rebased")
            }
        }
    }
}
