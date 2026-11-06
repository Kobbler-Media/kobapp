package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import io.ktor.client.HttpClient
import kotlin.test.Test

/**
 * A phone header used to lead with "MOVIE · 2017 · 1H 39M · R · ★ 3.5", which wrapped
 * mid-item, then a separate row of genre chips, then five icons beside Resume.
 */
@OptIn(ExperimentalTestApi::class)
class MovieHeaderTest {

    private val raw = Movie(
        id = "m1", title = "Raw", year = 2017, posterUrl = "", filePath = "/raw.mkv",
        duration = "1h 39m", contentRating = "R", rating = 3.5f, genres = listOf("Horror", "Drama"),
        runtimeMinutes = 99, watchPositionSeconds = 300,
    )
    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    private fun movie(formFactor: FormFactor, width: Int, height: Int, test: androidx.compose.ui.test.DesktopComposeUiTest.() -> Unit) =
        runUiTest(width = width, height = height) {
            setContent {
                KobappTheme(formFactor = formFactor) {
                    MovieDetailScreen(movie = raw, api = api, formFactor = formFactor, onBack = {}, onPlay = {}, onWatchedChanged = {})
                }
            }
            waitForIdle()
            test()
        }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.headerReads() {
        onNodeWithText("Raw 2017").assertExists()
        onAllNodesWithText("MOVIE ·", substring = true).assertCountEquals(0)
        onNodeWithText("1h 39m").assertExists()
        onNodeWithText("Horror, Drama").assertExists()
    }

    @Test
    fun `phone header puts the year by the title and the facts on one wrapping line`() =
        movie(FormFactor.Phone, 412, 900) { headerReads() }

    @Test
    fun `wide header reads the same`() = movie(FormFactor.Desktop, 1280, 900) { headerReads() }

    @Test
    fun `on a phone, what does not fit beside Resume is in More`() = movie(FormFactor.Phone, 412, 900) {
        onNodeWithContentDescription("More").assertExists()
        assertOfferedOnce("From start", "Mark watched", "Add to your list")
    }

    @Test
    fun `beside a poster, the row has room for everything`() = movie(FormFactor.Desktop, 1280, 900) {
        onAllNodesWithContentDescription("More").assertCountEquals(0)
        onNodeWithContentDescription("Mark watched").assertExists()
        onNodeWithContentDescription("Add to your list").assertExists()
    }
}
