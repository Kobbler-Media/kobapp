package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Typing on a TV remote is slow, so search offers whole titles to pick: before anything is
 * typed, what is trending on TMDB; while typing, titles, authors, channels and guide
 * programmes that the query starts a word of. Picking one searches for it.
 */
@OptIn(ExperimentalTestApi::class)
class SearchSuggestionsTest {

    private val requests = CopyOnWriteArrayList<String>()

    private val routes = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> String>(
        "/api/v1/admin/search/suggestions" to { exchange ->
            val query = exchange.requestURI.query.orEmpty()
            requests += query
            if (query.startsWith("q=&")) {
                """{"suggestions":[{"title":"The Last of Us","media_type":"tv","source":"tmdb"}]}"""
            } else {
                """{"suggestions":[{"title":"Breakin'","media_type":"movie","source":"library"},{"title":"Breaking Bad","media_type":"tv","source":"tmdb"}]}"""
            }
        },
        "/api/v1/admin/search" to { _ -> """{"top_hits":[],"remaining":[]}""" },
    )

    private fun overlay(query: String, scope: SearchScope, picked: MutableList<String>, test: suspend androidx.compose.ui.test.DesktopComposeUiTest.() -> Unit) =
        runUiTest(width = 1280, height = 900) {
            UserSettings.remove("search_recents")
            requests.clear()
            StubServer(routes).use { stub ->
                setContent {
                    KobappTheme(formFactor = FormFactor.TV) {
                        SearchOverlay(
                            api = KobblerApi(HttpClient(), stub.url, "token"),
                            scope = scope,
                            onScopeChange = {},
                            query = query,
                            onQueryChange = { picked += it },
                            onDismiss = {},
                            onResultClick = {},
                            formFactor = FormFactor.TV,
                            autoFocusQuery = false,
                        )
                    }
                }
                test()
            }
        }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `an empty query offers trending titles`() {
        val picked = mutableListOf<String>()
        overlay("", SearchScope.All, picked) {
            awaitText("The Last of Us")
            onNodeWithText("The Last of Us").performClick()
            waitForIdle()
        }
        assertEquals(listOf("The Last of Us"), picked)
        assertEquals("q=&media=movie,tv,book&limit=8", requests.single())
    }

    @Test
    fun `a partial query offers titles it completes`() {
        val picked = mutableListOf<String>()
        overlay("break", SearchScope.Movies, picked) {
            awaitText("Breaking Bad")
            onNodeWithText("Breaking Bad").performClick()
            waitForIdle()
        }
        assertEquals(listOf("Breaking Bad"), picked)
        assertTrue(requests.all { it == "q=break&media=movie&limit=8" }, "requests: $requests")
    }

    @Test
    fun `every scope asks for suggestions of what it searches`() {
        val expected = mapOf(
            SearchScope.All to "movie,tv,book",
            SearchScope.Movies to "movie",
            SearchScope.Tv to "tv",
            SearchScope.Books to "book",
            SearchScope.LiveTv to "livetv",
            SearchScope.Iptv to "iptv",
        )
        assertEquals(SearchScope.entries.toSet(), expected.keys)
        expected.forEach { (scope, media) ->
            val picked = mutableListOf<String>()
            overlay("break", scope, picked) {
                awaitText("Breaking Bad")
                onNodeWithText("Breaking Bad").performClick()
                waitForIdle()
            }
            assertEquals(listOf("Breaking Bad"), picked, "$scope")
            assertTrue(requests.isNotEmpty() && requests.all { it == "q=break&media=$media&limit=8" }, "$scope requests: $requests")
        }
    }
}
