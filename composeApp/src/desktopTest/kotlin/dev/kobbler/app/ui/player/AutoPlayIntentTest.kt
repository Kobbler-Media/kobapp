package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.ExperimentalTestApi
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.platform.FormFactor
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private val movie = Movie(id = "m1", title = "Movie", year = 2020, posterUrl = "", filePath = null)

private const val SEARCH = "/api/v1/admin/stream/search"
private const val RESOLVE = "/api/v1/admin/stream/resolve"
private const val WATCH_STATE = "/api/v1/admin/movies/m1/watch-state"

private val searchBody = """
{"options":[{"id":"o1","release_title":"Movie.2020","debrid_provider":"realdebrid",
 "debrid_provider_id":"p1","cached":true}],"errors":[]}
"""
private val resolveBody = """
{"session_id":"sess-1","method":"DirectPlay","direct_url":"/api/v1/admin/stream/sess-1/play",
 "duration_seconds":3600,"subtitles":[],"audio_tracks":[]}
"""

/**
 * `autoPlay` rides on the nav destination, so it is still set every time the detail
 * screen recomposes — including after the player it launched has been closed.
 */
@OptIn(ExperimentalTestApi::class)
class AutoPlayIntentTest {

    @Test
    fun closingAPausedPlayerDoesNotRelaunchIt() = runUiTest {
        StubServer(mapOf(
            SEARCH to { searchBody },
            RESOLVE to { resolveBody },
            WATCH_STATE to { """{"position_seconds":1200}""" },
        )).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token").apply { canStream = true }
            val player = FakePlayer().also { it.durationMs.value = 3_600_000 }
            val gps = GlobalPlayerState()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            setContent {
                InlineMoviePlayer(
                    movie = movie, api = api, formFactor = FormFactor.Phone,
                    player = player, gps = gps, autoPlay = true, onBack = {},
                )
            }

            // isFullscreen is the last thing the launch sets, so the load has finished.
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.mediaId == "m1" && gps.isFullscreen }
            assertEquals(1_200_000L, gps.pendingSeekMs)

            // Back out of a paused player: the scaffold's BackHandler stops the session.
            runOnIdle { gps.stop(player, api, scope) }
            waitForIdle()
            Thread.sleep(2_000)
            waitForIdle()

            assertNull(gps.mediaId, "the detail screen relaunched the player after it was closed")
        }
    }

    /**
     * The same intent, spent by the other launch path. A title that is in the library
     * never reaches the debrid branch above — it is opened by the prewarm, which is
     * deliberately re-armed when the player lets go so that the next Play is warm. Left
     * holding the autoplay intent, that re-arming opened the player again with nothing
     * on its way to play in it.
     */
    @Test
    fun closingAPlayerDoesNotRelaunchALibraryTitle() = runUiTest {
        StubServer(mapOf(
            "/api/v1/admin/playback/sessions" to {
                """{"session_id":"s1","direct_url":"/api/v1/admin/stream/s1/play","method":"direct"}"""
            },
            WATCH_STATE to { """{"position_seconds":1200}""" },
        )).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 3_600_000 }
            val gps = GlobalPlayerState()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val libraryMovie = movie.copy(filePath = "/library/movie.mkv")

            setContent {
                InlineMoviePlayer(
                    movie = libraryMovie, api = api, formFactor = FormFactor.TV,
                    player = player, gps = gps, autoPlay = true, onBack = {},
                )
            }

            waitUntil(timeoutMillis = UiTimeoutMs) { gps.mediaId == "m1" && gps.isFullscreen }

            runOnIdle { gps.stop(player, api, scope) }
            waitForIdle()
            Thread.sleep(2_000)
            waitForIdle()

            assertNull(gps.mediaId, "the prewarm relaunched the player after it was closed")
            assertFalse(gps.isStarting, "the player was left waiting on a stream nothing would play")
        }
    }
}
