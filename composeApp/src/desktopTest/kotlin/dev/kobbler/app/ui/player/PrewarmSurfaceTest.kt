package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.platform.FormFactor
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Browsing a title must not open its stream.
 *
 * A decoder is chosen when the elementary stream opens, and on Android it can only be a
 * hardware one if there is a surface to hand it. The player owns that surface and has
 * none until it is on screen, so a prewarm that opened the stream from a detail page
 * chose the software decoder — and then the tap that followed found the title already
 * loaded, unmuted it, and played the whole film on libavcodec.
 *
 * So a browse prewarms the session and stops. The tap raises the player first, and opens
 * the stream against a real window. Autoplay still loads, because the player is on its
 * way to the screen either way.
 */
@OptIn(ExperimentalTestApi::class)
class PrewarmSurfaceTest {

    private val movie = Movie(
        id = "m1", title = "Movie", year = 2020, posterUrl = "",
        filePath = "/library/movie.mkv",
    )

    private val routes = mapOf(
        "/api/v1/admin/playback/sessions" to { _: com.sun.net.httpserver.HttpExchange ->
            """{"session_id":"s1","direct_url":"/api/v1/admin/stream/s1/play","method":"direct"}"""
        },
        "/api/v1/admin/movies/m1/watch-state" to { _: com.sun.net.httpserver.HttpExchange ->
            """{"position_seconds":600}"""
        },
    )

    @Test
    fun `opening a detail page does not open the stream`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 3_600_000 }
            val gps = GlobalPlayerState()

            setContent {
                InlineMoviePlayer(
                    movie = movie, api = api, formFactor = FormFactor.TV,
                    player = player, gps = gps, autoPlay = false, onBack = {},
                )
            }
            waitForIdle()
            mainClock.advanceTimeBy(5_000)
            waitForIdle()

            assertTrue(
                player.calls.none { it == "load" },
                "the stream was opened before the player had a surface: ${player.calls}",
            )
        }
    }
}
