package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/** A page narrowed to a category, or to continuing shows, still has an A–Z bar, of its own letters. */
@OptIn(ExperimentalTestApi::class)
class BrowseFilteredAlphabetTest {

    private fun HttpExchange.param(name: String): String? =
        requestURI.query.orEmpty().split('&').firstOrNull { it.startsWith("$name=") }?.removePrefix("$name=")

    private fun page(exchange: HttpExchange): String {
        val titles = when {
            exchange.param("genre") == "Family" -> listOf("Quokka", "Toybox")
            exchange.param("status") == "continuing" -> listOf("Carousel")
            else -> listOf("Aardvark", "Gorefest", "Toybox")
        }
        return titles.joinToString(",", prefix = """{"items":[""", postfix = "]}") { """{"id":"$it","title":"$it","year":2020}""" }
    }

    private fun letters(exchange: HttpExchange): String {
        val letters = when {
            exchange.param("genre") == "Family" -> listOf("Q", "T")
            exchange.param("status") == "continuing" -> listOf("C")
            else -> listOf("A", "G", "T")
        }
        return letters.joinToString(",", prefix = """{"letters":[""", postfix = "]}") { "\"$it\"" }
    }

    private fun browse(tv: Boolean, test: DesktopComposeUiTest.(jumps: List<String>) -> Unit) = runUiTest(width = 1280, height = 900) {
        DataCache.clear()
        val jumps = CopyOnWriteArrayList<String>()
        val firstByLetter: (HttpExchange) -> String = { exchange ->
            jumps += exchange.requestURI.query.orEmpty()
            """{"id":"Toybox","title":"Toybox"}"""
        }
        StubServer(
            mapOf(
                "/api/v1/admin/movies" to ::page,
                "/api/v1/admin/tv/shows" to ::page,
                "/api/v1/admin/movies/available-letters" to ::letters,
                "/api/v1/admin/tv/shows/available-letters" to ::letters,
                "/api/v1/admin/movies/first-by-letter" to firstByLetter,
                "/api/v1/admin/tv/shows/first-by-letter" to firstByLetter,
                "/api/v1/admin/movies/genres" to { _ -> """{"genres":[{"name":"Family","count":2}]}""" },
                "/api/v1/admin/tv/shows/genres" to { _ -> """{"genres":[{"name":"Family","count":2}]}""" },
                "/api/v1/admin/discover" to { _ -> """{"watch_next":[],"recently_added":[],"sections":[]}""" },
                "/api/v1/admin/dashboard" to { _ -> "{}" },
            ),
        ).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            setContent {
                KobappTheme(formFactor = FormFactor.TV) {
                    FocusScopeHost(id = "browse") {
                        if (tv) TvShowsScreen(api = api, formFactor = FormFactor.TV, onShowClick = {})
                        else MoviesScreen(api = api, formFactor = FormFactor.TV, onMovieClick = {})
                    }
                }
            }
            waitUntil(timeoutMillis = UiTimeoutMs) { has("G") }
            test(jumps)
        }
    }

    private fun DesktopComposeUiTest.has(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a category's bar has the category's letters and jumps within it`() {
        for (tv in listOf(false, true)) browse(tv) { jumps ->
            onAllNodesWithText("Categories").onFirst().performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { has("Family") }
            onAllNodesWithText("Family").onFirst().performClick()
            waitUntil("tv=$tv: the bar shows the category's letters", timeoutMillis = UiTimeoutMs) { has("Q") && !has("G") }
            onNodeWithText("T").performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { jumps.isNotEmpty() }
            assertTrue(jumps.single().contains("genre=Family"), "tv=$tv: jump asked ${jumps.single()}")
        }
    }

    @Test
    fun `continuing shows have a bar of their own letters`() = browse(tv = true) { jumps ->
        onNodeWithText("Continuing").performClick()
        waitUntil("the bar shows continuing letters", timeoutMillis = UiTimeoutMs) { has("C") && !has("G") }
        onNodeWithText("C").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { jumps.isNotEmpty() }
        assertTrue(jumps.single().contains("status=continuing"), "jump asked ${jumps.single()}")
    }
}
