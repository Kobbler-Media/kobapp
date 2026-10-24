package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** A browse page narrows to one category, picked from the genres the server says it has. */
@OptIn(ExperimentalTestApi::class)
class BrowseCategoriesTest {

    private val titles = mapOf(
        "Family" to listOf("Toybox", "Tiny Toons"),
        null to listOf("Toybox", "Tiny Toons", "Gorefest"),
    )

    private fun page(exchange: HttpExchange): String {
        val genre = exchange.requestURI.query.orEmpty().split('&')
            .firstOrNull { it.startsWith("genre=") }?.removePrefix("genre=")
        val items = titles.getValue(genre).joinToString(",") { """{"id":"$it","title":"$it","year":2020}""" }
        return """{"items":[$items]}"""
    }

    private val genres = """{"genres":[{"name":"Family","count":2},{"name":"Horror","count":1}]}"""

    private fun browse(
        tv: Boolean,
        formFactor: FormFactor = FormFactor.Desktop,
        width: Int = 1280,
        test: DesktopComposeUiTest.() -> Unit,
    ) =
        runUiTest(width = width, height = 900) {
            DataCache.clear()
            StubServer(
                mapOf(
                    "/api/v1/admin/movies" to ::page,
                    "/api/v1/admin/tv/shows" to ::page,
                    "/api/v1/admin/movies/genres" to { _ -> genres },
                    "/api/v1/admin/tv/shows/genres" to { _ -> genres },
                    "/api/v1/admin/discover" to { _ -> """{"watch_next":[],"recently_added":[],"sections":[]}""" },
                    "/api/v1/admin/dashboard" to { _ -> "{}" },
                ),
            ).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                setContent {
                    KobappTheme(formFactor = formFactor) {
                        FocusScopeHost(id = "browse") {
                            if (tv) TvShowsScreen(api = api, formFactor = formFactor, onShowClick = {})
                            else MoviesScreen(api = api, formFactor = formFactor, onMovieClick = {})
                        }
                    }
                }
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Gorefest").fetchSemanticsNodes().isNotEmpty() }
                test()
            }
        }

    @Test
    fun `picking a category shows only that category`() {
        for (tv in listOf(false, true)) browse(tv) {
            onAllNodesWithText("Categories").onFirst().performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Horror").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("Family").onFirst().performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Gorefest").fetchSemanticsNodes().isEmpty() }
            assertTrue(onAllNodesWithText("Toybox").fetchSemanticsNodes().isNotEmpty(), "tv=$tv")

            onAllNodesWithText("Family").onFirst().performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("All categories").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("All categories").onFirst().performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Gorefest").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun DesktopComposeUiTest.pickFamily() {
        onAllNodesWithText("Categories").onFirst().performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Horror").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Family").onFirst().performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Gorefest").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `the category is a segment beside all library, and all library clears it`() {
        for (tv in listOf(false, true)) browse(tv) {
            pickFamily()
            onAllNodesWithText("Categories").assertCountEquals(0)
            onNodeWithText("All library").performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Gorefest").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Categories").assertExists()
        }
    }

    @Test
    fun `continuing replaces a picked category`() = browse(tv = true) {
        pickFamily()
        onNodeWithText("Continuing").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Categories").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `the title and the selector share a centre line`() {
        for (tv in listOf(false, true)) for (phone in listOf(false, true)) browse(
            tv,
            formFactor = if (phone) FormFactor.Phone else FormFactor.Desktop,
            width = if (phone) 412 else 1280,
        ) {
            val title = onNodeWithText(if (tv) "TV Shows" else "Movies").getBoundsInRoot()
            val selector = onNodeWithText("Categories").getBoundsInRoot()
            val drift = ((title.top + title.bottom) - (selector.top + selector.bottom)) / 2
            assertTrue(drift.value in -2f..2f, "tv=$tv phone=$phone: selector sits ${drift} off the title's centre")
        }
    }

    @Test
    fun `the grid has a heading of its own that carries the count`() {
        for (tv in listOf(false, true)) browse(tv) {
            val all = if (tv) "All shows" else "All movies"
            onNodeWithText(all).assertExists()
            onNodeWithText("3").assertExists()
            onAllNodesWithText("in library", substring = true).assertCountEquals(0)
            pickFamily()
            onNodeWithText("2").assertExists()
            assertTrue(onAllNodesWithText("Family").fetchSemanticsNodes().size >= 2, "tv=$tv: the grid heading did not name the category")
        }
    }
}
