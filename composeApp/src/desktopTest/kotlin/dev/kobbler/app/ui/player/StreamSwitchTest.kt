package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
)

private val startedShow = show.copy(
    continueEpisode = ContinueEpisode(seasonIdx = 0, episodeIdx = 0, episodeId = "ep-1", positionSeconds = 600),
)

private fun option(hash: String) = """{"id":"$hash","release_title":"Release.$hash","debrid_provider":"realdebrid",
    "debrid_provider_id":"p1","info_hash":"$hash","cached":true,"availability":"ready"}"""

private fun routes(resolving: CountDownLatch = CountDownLatch(0)) = mapOf<String, (HttpExchange) -> String>(
    LOCAL to { option("localhash") },
    SEARCH to { """{"options":[${option("searchhash")}],"errors":[]}""" },
    RESOLVE to {
        resolving.await(10, TimeUnit.SECONDS)
        """{"session_id":"sess-1","method":"DirectPlay","direct_url":"/api/v1/admin/stream/sess-1/play",
            "duration_seconds":6000,"subtitles":[],"audio_tracks":[]}"""
    },
    "/api/v1/admin/movies/m1/watch-state" to { """{"watched":false,"position_seconds":600}""" },
    "/api/v1/admin/tv/episodes/ep-1/watch-state" to { """{"watched":false,"position_seconds":600}""" },
)

/**
 * The picker used to belong to the detail screen, which sits under the player: switching
 * from the player drew it out of sight, and with a release played from disk it had never
 * searched, so it came up empty.
 */
@OptIn(ExperimentalTestApi::class)
class StreamSwitchTest {

    private fun ComposeUiTest.switchesFromThePlayer(
        mediaId: String,
        screen: (KobblerApi, GlobalPlayerState) -> @androidx.compose.runtime.Composable () -> Unit,
    ) = StubServer(routes()).use { stub ->
        val api = KobblerApi(HttpClient(), stub.url, "token").apply { canStream = true }
        val gps = GlobalPlayerState()
        setContent {
            Box {
                screen(api, gps)()
                StreamPickerHost(gps, api)
            }
        }
        waitForIdle()
        onNodeWithContentDescription("From start").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { gps.mediaId == mediaId && gps.isFullscreen }

        runOnIdle { gps.onSwitchStream!!() }
        waitUntil(timeoutMillis = UiTimeoutMs) { gps.streamPicker?.result?.options?.isNotEmpty() == true }
        onNodeWithText("Stream options").assertExists()
        onNodeWithText("Release.searchhash").performClick()

        waitUntil(timeoutMillis = UiTimeoutMs) { stub.requestBodies[RESOLVE]?.contains("searchhash") == true }
        assertNull(gps.streamPicker)
        waitUntil(timeoutMillis = UiTimeoutMs) { gps.mediaId == mediaId && gps.isFullscreen }
    }

    @Test
    fun anEpisodeSwitchedFromThePlayerListsReleasesAndPlaysThePick() = runUiTest {
        switchesFromThePlayer("ep-1") { api, gps ->
            {
                InlineTvPlayer(
                    show = startedShow, api = api, formFactor = FormFactor.TV,
                    player = FakePlayer().also { it.durationMs.value = 3_000_000 }, gps = gps, onBack = {},
                )
            }
        }
    }

    @Test
    fun aMovieSwitchedFromThePlayerListsReleasesAndPlaysThePick() = runUiTest {
        switchesFromThePlayer("m1") { api, gps ->
            {
                InlineMoviePlayer(
                    movie = movie, api = api, formFactor = FormFactor.TV,
                    player = FakePlayer().also { it.durationMs.value = 6_000_000 }, gps = gps, onBack = {},
                )
            }
        }
    }

    @Test
    fun aReleasePickedFromTheEpisodeListOpensThePlayerWhileItResolves() = runUiTest {
        val resolving = CountDownLatch(1)
        StubServer(routes(resolving)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token").apply { canStream = true }
            val gps = GlobalPlayerState()
            setContent {
                Box {
                    InlineTvPlayer(
                        show = show, api = api, formFactor = FormFactor.TV,
                        player = FakePlayer().also { it.durationMs.value = 3_000_000 }, gps = gps, onBack = {},
                    )
                    StreamPickerHost(gps, api)
                }
            }
            waitForIdle()
            onAllNodesWithContentDescription("Stream options").onFirst().performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.streamPicker?.result?.options?.isNotEmpty() == true }
            onNodeWithText("Release.searchhash").performClick()

            waitUntil(timeoutMillis = UiTimeoutMs) { stub.requestBodies.containsKey(RESOLVE) }
            assertTrue(gps.isPreparing && gps.isFullscreen, "nothing on screen while the pick resolves")
            assertEquals("Show", gps.title)
            resolving.countDown()
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.mediaId == "ep-1" }
        }
    }
}
