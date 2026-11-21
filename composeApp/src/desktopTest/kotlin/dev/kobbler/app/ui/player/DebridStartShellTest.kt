package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
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
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.fail

private const val SHELL_WAIT_MS = 5_000L

private val movie = Movie(
    id = "m1", title = "Movie", year = 2020, posterUrl = "http://127.0.0.1:1/poster.jpg",
    filePath = null, runtimeMinutes = 100, watchPositionSeconds = 600,
)

private val show = TvShow(
    id = "show-1", title = "Show", year = 2020, posterUrl = "http://127.0.0.1:1/poster.jpg",
    seasons = listOf(Season(number = 1, episodes = listOf(Episode(id = "ep-1", number = 1, title = "One", filePath = null)))),
    continueEpisode = ContinueEpisode(seasonIdx = 0, episodeIdx = 0, episodeId = "ep-1", positionSeconds = 600),
)

/**
 * A debrid title spends seconds finding a stream before anything loads. That wait has to be
 * the start shell over the title's artwork, not the bare black player surface.
 */
@OptIn(ExperimentalTestApi::class)
class DebridStartShellTest {

    private class Case(
        val name: String,
        val continueLink: String?,
        val content: @Composable (KobblerApi, GlobalPlayerState, Boolean) -> Unit,
        val tap: (ComposeUiTest.() -> Unit)? = null,
    )

    private val cases = listOf(
        Case("continue watching a movie", "/movies/m1", { api, gps, autoPlay ->
            InlineMoviePlayer(movie = movie, api = api, formFactor = FormFactor.Phone, player = FakePlayer(), gps = gps, autoPlay = autoPlay, onBack = {})
        }),
        Case("continue watching an episode", "/tv/show-1", { api, gps, autoPlay ->
            InlineTvPlayer(show = show, api = api, formFactor = FormFactor.Phone, player = FakePlayer(), gps = gps, autoPlay = autoPlay, onBack = {})
        }),
        Case("play a show from its page", null, { api, gps, autoPlay ->
            InlineTvPlayer(show = show, api = api, formFactor = FormFactor.Phone, player = FakePlayer(), gps = gps, autoPlay = autoPlay, onBack = {})
        }, tap = { onNodeWithContentDescription("From start").performClick() }),
    )

    @Test
    fun theWaitForADebridStreamShowsTheStartShell() {
        val black = mutableListOf<String>()
        for (case in cases) runUiTest {
            val gate = CountDownLatch(1)
            val hang = { _: HttpExchange -> gate.await(); "{}" }
            StubServer(
                mapOf(
                    "/api/v1/admin/stream/search" to hang,
                    "/api/v1/admin/playback/sessions" to hang,
                ),
            ).use { stub ->
                try {
                    val api = KobblerApi(HttpClient(), stub.url, "token").apply { canStream = true }
                    val gps = GlobalPlayerState()
                    case.continueLink?.let { gps.beginPreparing(id = it, newTitle = "Title", newSubtitle = null, newPosterUrl = null, stream = false) }
                    setContent {
                        Box(Modifier.fillMaxSize()) {
                            case.content(api, gps, case.continueLink != null)
                            if (gps.isFullscreen) {
                                FullscreenVideoPlayer(
                                    player = FakePlayer(), state = gps, api = api, formFactor = FormFactor.Phone,
                                    onBack = {}, isFullscreen = true, videoSurface = { },
                                )
                            }
                        }
                    }
                    waitForIdle()
                    case.tap?.invoke(this)
                    waitUntil(timeoutMillis = UiTimeoutMs) { gps.isFullscreen }
                    try {
                        waitUntil(timeoutMillis = SHELL_WAIT_MS) {
                            onAllNodesWithText("Searching…").fetchSemanticsNodes().isNotEmpty()
                        }
                    } catch (_: Throwable) {
                        black += "${case.name} (isPreparing=${gps.isPreparing}, isStreamStart=${gps.isStreamStart}, requests=${stub.requestBodies.keys})"
                    }
                } finally {
                    gate.countDown()
                }
            }
        }
        if (black.isNotEmpty()) fail("black player while the stream is found: ${black.joinToString("; ")}")
    }
}
