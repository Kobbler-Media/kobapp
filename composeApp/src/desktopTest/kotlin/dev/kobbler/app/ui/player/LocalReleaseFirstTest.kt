package dev.kobbler.app.ui.player

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.ContinueEpisode
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val LOCAL = "/api/v1/admin/stream/local"
private const val SEARCH = "/api/v1/admin/stream/search"
private const val RESOLVE = "/api/v1/admin/stream/resolve"

private val movie = Movie(
    id = "m1", title = "Movie", year = 2020, posterUrl = "",
    filePath = null, runtimeMinutes = 100, watchPositionSeconds = 600,
)

private val show = TvShow(
    id = "show-1", title = "Show", year = 2020, posterUrl = "",
    seasons = listOf(Season(number = 1, episodes = listOf(Episode(id = "ep-1", number = 1, title = "One", filePath = null)))),
    continueEpisode = ContinueEpisode(seasonIdx = 0, episodeIdx = 0, episodeId = "ep-1", positionSeconds = 600),
)

private fun option(hash: String) = """{"id":"$hash","release_title":"Release.$hash","debrid_provider":"realdebrid",
    "debrid_provider_id":"p1","info_hash":"$hash","cached":true,"availability":"ready"}"""

private val routes = mapOf<String, (HttpExchange) -> String>(
    LOCAL to { option("localhash") },
    SEARCH to { """{"options":[${option("searchhash")}],"errors":[]}""" },
    RESOLVE to {
        """{"session_id":"sess-1","method":"DirectPlay","direct_url":"/api/v1/admin/stream/sess-1/play",
            "duration_seconds":6000,"subtitles":[],"audio_tracks":[]}"""
    },
    "/api/v1/admin/movies/m1/watch-state" to { """{"watched":false,"position_seconds":600}""" },
    "/api/v1/admin/tv/episodes/ep-1/watch-state" to { """{"watched":false,"position_seconds":600}""" },
)

/** A release already on disk used to wait on a fresh indexer search, which could then pick another. */
@OptIn(ExperimentalTestApi::class)
class LocalReleaseFirstTest {

    private fun ComposeUiTest.playsTheLocalRelease(mediaId: String, content: (KobblerApi, GlobalPlayerState) -> @androidx.compose.runtime.Composable () -> Unit) =
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token").apply { canStream = true }
            val gps = GlobalPlayerState()
            setContent { content(api, gps)() }
            waitForIdle()
            onNodeWithContentDescription("From start").performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.mediaId == mediaId && gps.isFullscreen }
            val resolve = stub.requestBodies.getValue(RESOLVE)
            assertTrue(resolve.contains("localhash"), "resolved another release: $resolve")
            assertFalse(stub.requestBodies.containsKey(SEARCH), "searched with the release on disk")
        }

    @Test
    fun aMoviePlaysItsReleaseOnDiskWithoutSearching() = runUiTest {
        playsTheLocalRelease("m1") { api, gps ->
            {
                InlineMoviePlayer(
                    movie = movie, api = api, formFactor = FormFactor.TV,
                    player = FakePlayer().also { it.durationMs.value = 6_000_000 }, gps = gps, onBack = {},
                )
            }
        }
    }

    @Test
    fun anEpisodePlaysItsReleaseOnDiskWithoutSearching() = runUiTest {
        playsTheLocalRelease("ep-1") { api, gps ->
            {
                InlineTvPlayer(
                    show = show, api = api, formFactor = FormFactor.TV,
                    player = FakePlayer().also { it.durationMs.value = 3_000_000 }, gps = gps, onBack = {},
                )
            }
        }
    }
}
