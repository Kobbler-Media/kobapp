package dev.kobbler.app.ui.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** A detail page stands on its title's own backdrop, the way Home does, on every screen shape. */
@OptIn(ExperimentalTestApi::class)
class DetailBackdropTest {

    @Test
    fun `movie and show pages load their backdrop`() {
        for (formFactor in listOf(FormFactor.Desktop, FormFactor.TV)) {
            for (kind in listOf("movie", "show")) {
                runDesktopComposeUiTest(width = 1280, height = 800) {
                    StubServer(emptyMap()).use { stub ->
                        val backdrop = "${stub.url}/img/$kind-backdrop"
                        val poster = "${stub.url}/img/$kind-poster"
                        val api = KobblerApi(HttpClient(), stub.url, "token")
                        setContent {
                            KobappTheme(formFactor = formFactor) {
                                when (kind) {
                                    "movie" -> MovieDetailScreen(
                                        movie = Movie(id = "m1", title = "Movie", year = 2020, posterUrl = poster, backdropUrl = backdrop),
                                        api = api, formFactor = formFactor, onBack = {}, onPlay = {}, onWatchedChanged = {},
                                    )
                                    else -> TvShowDetailScreen(
                                        show = TvShow(id = "s1", title = "Show", year = 2020, posterUrl = poster, backdropUrl = backdrop),
                                        formFactor = formFactor, onBack = {}, onPlayEpisode = { _, _, _ -> },
                                    )
                                }
                            }
                        }
                        waitUntil(timeoutMillis = 10_000) { "/img/$kind-poster" in stub.requestBodies.keys }
                        repeat(50) {
                            if ("/img/$kind-backdrop" in stub.requestBodies.keys) return@use
                            mainClock.advanceTimeBy(500)
                            waitForIdle()
                            Thread.sleep(50)
                        }
                        assertTrue(false, "$formFactor $kind page never asked for its backdrop")
                    }
                }
            }
        }
    }
}
