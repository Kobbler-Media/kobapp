package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** The header's filters and search button hold still while the page's A–Z bar loads in. */
@OptIn(ExperimentalTestApi::class)
class BrowseHeaderSteadyTest {

    private val page = """{"items":[{"id":"t1","title":"Toybox","year":2020}]}"""

    private class Edges(val categories: Dp, val search: Dp)

    private fun DesktopComposeUiTest.edges(title: String) = Edges(
        categories = onNodeWithText("Categories").getBoundsInRoot().right,
        search = onNodeWithContentDescription("Search $title").getBoundsInRoot().right,
    )

    private fun headerEdges(tv: Boolean): Pair<Edges, Edges> {
        val title = if (tv) "TV Shows" else "Movies"
        val lettersGate = CountDownLatch(1)
        lateinit var result: Pair<Edges, Edges>
        runUiTest(width = 1280, height = 900) {
            DataCache.clear()
            StubServer(
                mapOf(
                    "/api/v1/admin/movies" to { _ -> page },
                    "/api/v1/admin/tv/shows" to { _ -> page },
                    "/api/v1/admin/movies/available-letters" to { _ -> lettersGate.await(30, TimeUnit.SECONDS); """{"letters":["Q","T"]}""" },
                    "/api/v1/admin/tv/shows/available-letters" to { _ -> lettersGate.await(30, TimeUnit.SECONDS); """{"letters":["Q","T"]}""" },
                    "/api/v1/admin/discover" to { _ -> """{"watch_next":[],"recently_added":[],"sections":[]}""" },
                    "/api/v1/admin/dashboard" to { _ -> "{}" },
                ),
            ).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                setContent {
                    KobappTheme(formFactor = FormFactor.TV) {
                        FocusScopeHost(id = "browse") {
                            if (tv) TvShowsScreen(api = api, formFactor = FormFactor.TV, onShowClick = {}, onSearchClick = {})
                            else MoviesScreen(api = api, formFactor = FormFactor.TV, onMovieClick = {}, onSearchClick = {})
                        }
                    }
                }
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Toybox").fetchSemanticsNodes().isNotEmpty() }
                val before = edges(title)
                lettersGate.countDown()
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Q").fetchSemanticsNodes().isNotEmpty() }
                val settled = edges(title)
                if (tv) {
                    onNodeWithText("Continuing").performClick()
                    waitForIdle()
                    assertEquals(settled.search.value, edges(title).search.value, absoluteTolerance = 0.5f, "Continuing moved the search button")
                }
                result = before to settled
            }
        }
        return result
    }

    @Test
    fun `letters arriving do not shift the filters or the search button`() {
        for (tv in listOf(false, true)) {
            val (before, after) = headerEdges(tv)
            assertEquals(before.categories.value, after.categories.value, absoluteTolerance = 0.5f, "tv=$tv: Categories moved")
            assertEquals(before.search.value, after.search.value, absoluteTolerance = 0.5f, "tv=$tv: search button moved")
        }
    }

    @Test
    fun `movies and tv put the search button in the same place`() {
        assertEquals(headerEdges(tv = false).second.search.value, headerEdges(tv = true).second.search.value, absoluteTolerance = 0.5f)
    }
}
