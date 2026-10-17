package dev.kobbler.app.ui.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/** An admin decides, on the title's own page, whether a Kids profile sees it. */
@OptIn(ExperimentalTestApi::class)
class DetailKidsAccessTest {

    private fun page(kind: String, admin: Boolean, test: androidx.compose.ui.test.DesktopComposeUiTest.(patches: List<String>) -> Unit) =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val path = if (kind == "movie") "/api/v1/admin/movies/t1" else "/api/v1/admin/tv/shows/t1"
            val patches = CopyOnWriteArrayList<String>()
            lateinit var stub: StubServer
            stub = StubServer(
                mapOf(
                    path to { exchange ->
                        if (exchange.requestMethod == "PATCH") {
                            patches += stub.requestBodies.getValue(path)
                            ""
                        } else {
                            """{"id":"t1","title":"Title","kids_override":true,"kids_allowed":true}"""
                        }
                    },
                ),
            )
            stub.use {
                val api = KobblerApi(HttpClient(), stub.url, "token", canManageKids = admin)
                setContent {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        if (kind == "movie") {
                            MovieDetailScreen(
                                movie = Movie(id = "t1", title = "Title", year = 2020, posterUrl = "", contentRating = "R"),
                                api = api, formFactor = FormFactor.Desktop, onBack = {}, onPlay = {}, onWatchedChanged = {},
                            )
                        } else {
                            TvShowDetailScreen(
                                show = TvShow(id = "t1", title = "Title", year = 2020, posterUrl = "", contentRating = "TV-MA"),
                                formFactor = FormFactor.Desktop, onBack = {}, onPlayEpisode = { _, _, _ -> }, api = api,
                            )
                        }
                    }
                }
                waitForIdle()
                test(patches)
            }
        }

    @Test
    fun `admin allows a title for kids profiles`() {
        for (kind in listOf("movie", "show")) page(kind, admin = true) { patches ->
            assertTrue(onAllNodesWithText("Hidden from Kids profiles").fetchSemanticsNodes().isNotEmpty(), kind)
            onAllNodesWithText("Allow").onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Shown on Kids profiles").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(patches.any { it.contains("\"kids_override\":true") }, "$kind: $patches")
        }
    }

    @Test
    fun `only admins see the control`() {
        for (kind in listOf("movie", "show")) page(kind, admin = false) {
            assertTrue(onAllNodesWithText("Hidden from Kids profiles").fetchSemanticsNodes().isEmpty(), kind)
        }
    }
}
