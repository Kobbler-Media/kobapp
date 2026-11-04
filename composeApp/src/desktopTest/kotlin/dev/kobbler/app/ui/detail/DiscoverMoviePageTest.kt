package dev.kobbler.app.ui.detail

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.ui.detail.tvshow.detailPage
import dev.kobbler.app.ui.detail.tvshow.focusedText
import dev.kobbler.app.ui.navigation.Destination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A movie the library does not have, opened from Discover, laid out as its library page
 * would be: the poster, a synopsis set at reading width, one row of actions, and the
 * release strip under them — not squeezed into a column beside a panel repeating the
 * header.
 */
@OptIn(ExperimentalTestApi::class)
class DiscoverMoviePageTest {

    private val synopsis = "A robot left to clean up an abandoned Earth falls for a visiting probe."

    private fun routes(details: String = "") = mapOf<String, (HttpExchange) -> String>(
        "/api/v1/admin/discover/movie/tmdb/10681" to { _ ->
            """{"media_type":"movie","provider":"tmdb","details":{"title":"WALL-E","year":2008,"runtime":98,
               "description":"$synopsis","tagline":"After 700 years of doing what he was built for",
               "content_rating":"G","genres":["Animation","Family"],"rating":8.1,
               "cast":[{"name":"Ben Burtt","character":"WALL-E"}],
               "crew":[{"name":"Andrew Stanton","department":"Directing","job":"Director"},
                       {"name":"Jim Reardon","department":"Writing","job":"Screenplay"},
                       {"name":"Pete Docter","department":"Writing","job":"Story"},
                       {"name":"Jim Morris","department":"Production","job":"Producer"}],
               "release_date":"2008-06-22","identifiers":{"tmdb_id":"10681"}$details}}"""
        },
        "/api/v1/admin/stream/search" to { _ -> """{"options":[]}""" },
    )

    private fun discoverMovie(formFactor: FormFactor = FormFactor.TV, test: DesktopComposeUiTest.() -> Unit) =
        detailPage(Destination.DiscoverItemDetail("movie", "tmdb", "10681"), routes(), canStream = true, formFactor = formFactor) {
            waitForIdle()
            test()
        }

    private fun DesktopComposeUiTest.bounds(text: String): Rect =
        onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot

    private fun DesktopComposeUiTest.action(label: String): Rect =
        onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot

    @Test
    fun `the header carries what the library page shows`() = discoverMovie {
        onNodeWithText("After 700 years of doing what he was built for").assertExists()
        onNodeWithText("G").assertExists()
        onNodeWithText("Andrew Stanton").assertExists()
        onNodeWithText("Jim Reardon, Pete Docter").assertExists()
        assertEquals(0, onAllNodesWithText("Jim Morris").fetchSemanticsNodes().size)
    }

    @Test
    fun `nothing repeats the header in a panel beside it`() = listOf(FormFactor.TV, FormFactor.Desktop).forEach { formFactor ->
        discoverMovie(formFactor) {
            assertEquals(0, onAllNodesWithText("Details").fetchSemanticsNodes().size, "$formFactor")
            assertEquals(0, onAllNodesWithText("1 credited").fetchSemanticsNodes().size, "$formFactor")
        }
    }

    @Test
    fun `on a TV the actions sit on one row with the release strip under them`() = discoverMovie {
        assertEquals("Play", focusedText())
        actionsInOneRowAboveTheStrip()
    }

    @Test
    fun `a window too narrow for a third column lays out as the TV does`() = discoverMovie(FormFactor.Desktop) {
        actionsInOneRowAboveTheStrip()
    }

    private fun DesktopComposeUiTest.actionsInOneRowAboveTheStrip() {
        val play = bounds("Play")
        assertEquals(play.center.y, action("Stream options").center.y, 1f)
        assertEquals(play.center.y, action("Want").center.y, 1f)
        assertTrue(bounds("RELEASE AVAILABILITY").top > play.bottom, "strip ${bounds("RELEASE AVAILABILITY")} vs play $play")
        assertTrue(bounds(synopsis).width > 500f, "synopsis squeezed to ${bounds(synopsis).width}dp")
    }

    @Test
    fun `a wanted library movie on a TV keeps its release strip under the actions too`() =
        detailPage(
            Destination.MovieDetail("m1"),
            mapOf("/api/v1/admin/movies/m1" to { _ -> """{"id":"m1","title":"Upcoming","year":2099,"description":"$synopsis","want_status":"wanted","release_date":"2099-05-01"}""" }),
        ) {
            waitForIdle()
            assertTrue(bounds("RELEASE AVAILABILITY").top > bounds(synopsis).bottom, "strip ${bounds("RELEASE AVAILABILITY")} beside the synopsis")
            assertTrue(bounds(synopsis).width > 500f, "synopsis squeezed to ${bounds(synopsis).width}dp")
        }
}
