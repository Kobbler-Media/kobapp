package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
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

    private fun browse(tv: Boolean, test: DesktopComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 1280, height = 900) {
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
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        FocusScopeHost(id = "browse") {
                            if (tv) TvShowsScreen(api = api, formFactor = FormFactor.Desktop, onShowClick = {})
                            else MoviesScreen(api = api, formFactor = FormFactor.Desktop, onMovieClick = {})
                        }
                    }
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Gorefest").fetchSemanticsNodes().isNotEmpty() }
                test()
            }
        }

    @Test
    fun `picking a category shows only that category`() {
        for (tv in listOf(false, true)) browse(tv) {
            onAllNodesWithText("Categories").onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Horror").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("Family").onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Gorefest").fetchSemanticsNodes().isEmpty() }
            assertTrue(onAllNodesWithText("Toybox").fetchSemanticsNodes().isNotEmpty(), "tv=$tv")

            onAllNodesWithText("Family").onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("All categories").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("All categories").onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Gorefest").fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
