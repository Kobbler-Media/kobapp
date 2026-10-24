package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.ReleaseDates
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.GlobalPlayerState
import io.ktor.client.HttpClient
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** One strip, rendered by the shared movie layout for a Discover title and for a wanted one. */
@OptIn(ExperimentalTestApi::class)
class ReleaseAvailabilityStripTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    private fun shows(formFactor: FormFactor, vararg texts: String, content: @Composable () -> Unit) =
        listOf(1280 to 800, 400 to 860).forEach { (w, h) ->
            runUiTest(width = w, height = h) {
                setContent { KobappTheme(formFactor = formFactor) { content() } }
                waitForIdle()
                texts.forEach { text ->
                    assertEquals(1, onAllNodesWithText(text).fetchSemanticsNodes().size, "'$text' at ${w}dp")
                }
            }
        }

    private val wanted = Movie(
        id = "m1", title = "Upcoming", year = 2099, posterUrl = "", wantStatus = "wanted",
        releaseDate = LocalDate(2099, 5, 1),
    )

    @Test
    fun `a wanted movie with no file shows when it is coming`() =
        shows(FormFactor.Desktop, "RELEASE AVAILABILITY", "Coming May 1, 2099", "Wanted") {
            MovieDetailScreen(movie = wanted, api = api, formFactor = FormFactor.Desktop, onBack = {}, onPlay = {}, onWatchedChanged = {})
        }

    @Test
    fun `a wanted movie with release types shows each, and whose region they are`() =
        listOf(FormFactor.Desktop, FormFactor.TV).forEach { formFactor ->
            shows(formFactor, "Cinema", "Mar 3, 2026", "Digital", "Coming Jun 1, 2099", "Wanted", "Dates for ZZ") {
                MovieDetailScreen(
                    movie = wanted.copy(
                        releaseDates = ReleaseDates(region = "ZZ", theatrical = LocalDate(2026, 3, 3), digital = LocalDate(2099, 6, 1)),
                    ),
                    api = api, formFactor = formFactor, onBack = {}, onPlay = {}, onWatchedChanged = {},
                )
            }
        }

    @Test
    fun `a movie already on disk has no strip`() = runUiTest {
        setContent {
            KobappTheme(formFactor = FormFactor.Desktop) {
                MovieDetailScreen(
                    movie = wanted.copy(filePath = "/m.mkv"), api = api, formFactor = FormFactor.Desktop,
                    onBack = {}, onPlay = {}, onWatchedChanged = {},
                )
            }
        }
        waitForIdle()
        assertEquals(0, onAllNodesWithText("RELEASE AVAILABILITY").fetchSemanticsNodes().size)
    }

    @Test
    fun `a discover movie shows the strip and its details panel`() =
        shows(FormFactor.Desktop, "RELEASE AVAILABILITY", "Mar 31, 1999", "Physical", "Coming Jan 9, 2099", "Not in library") {
            DiscoverDetailScreen(
                detail = DiscoverDetail(
                    mediaType = "movie", provider = "tmdb", title = "The Matrix", year = 1999,
                    description = null, posterUrl = "", backdropUrl = null, genres = listOf("Action"),
                    rating = null, cast = emptyList(), network = null, runtime = 136, authors = emptyList(),
                    libraryId = null, wantStatus = null, isInLibrary = false, identifiers = emptyMap(),
                    providerId = "603", releaseDate = LocalDate(1999, 3, 31),
                    releaseDates = ReleaseDates(region = "ZZ", theatrical = LocalDate(1999, 3, 31), physical = LocalDate(2099, 1, 9)),
                ),
                api = api, formFactor = FormFactor.Desktop, player = null, gps = GlobalPlayerState(),
                onBack = {}, onViewInLibrary = { _, _ -> },
            )
        }
}
