package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A show the library does not have yet, opened from Discover.
 *
 * The page offered one button, "Monitor show", which asks the server for new episodes
 * only — so the one thing a viewer opens a 2026 show to do, ask for it, was the one thing
 * the page could not do. And once pressed it became a disabled pill with no focus target,
 * which on a remote is a dead page.
 */
@OptIn(ExperimentalTestApi::class)
class DiscoverWantTest {

    private val show = DiscoverDetail(
        mediaType = "tv", provider = "tmdb", title = "Last Seen", year = 2026,
        description = null, posterUrl = "", backdropUrl = null, genres = emptyList(),
        rating = null, cast = emptyList(), network = null, runtime = null,
        authors = emptyList(), libraryId = null, wantStatus = null, isInLibrary = false,
        identifiers = mapOf("tmdb_id" to "42"), providerId = "42",
    )

    private val routes = mapOf(
        "/api/v1/admin/tv/shows" to { _: com.sun.net.httpserver.HttpExchange -> """{"id":"show-9"}""" },
        "/api/v1/admin/tv/shows/show-9/monitor" to { _: com.sun.net.httpserver.HttpExchange -> "{}" },
    )

    private fun ComposeUiTest.openWantPicker(stub: StubServer): List<Pair<String, String>> {
        val viewed = showDiscoverPage(stub)
        onNodeWithText("Want").performClick()
        waitForIdle()
        return viewed
    }

    private fun ComposeUiTest.wantShow(stub: StubServer, pick: String): List<Pair<String, String>> {
        val viewed = openWantPicker(stub)
        onNodeWithText(pick).performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { viewed.isNotEmpty() }
        return viewed
    }

    private fun ComposeUiTest.showDiscoverPage(
        stub: StubServer,
        detail: State<DiscoverDetail> = mutableStateOf(show),
    ): List<Pair<String, String>> {
        val api = KobblerApi(HttpClient(), stub.url, "token")
        val viewed = mutableListOf<Pair<String, String>>()
        setContent {
            Box(Modifier.size(1280.dp, 720.dp)) {
                KobappTheme(formFactor = FormFactor.TV) {
                    DiscoverDetailScreen(
                        detail = detail.value,
                        api = api,
                        formFactor = FormFactor.TV,
                        player = null,
                        gps = GlobalPlayerState(),
                        onBack = {},
                        onViewInLibrary = { type, id -> viewed += type to id },
                    )
                }
            }
        }
        waitForIdle()
        return viewed
    }

    /** One word, as kobbler's discover page has it — not a per-type phrase for each. */
    @Test
    fun `the want button reads Want for shows and movies alike`() = runUiTest {
        StubServer(routes).use { stub ->
            val detail = mutableStateOf(show)
            showDiscoverPage(stub, detail)
            for (mediaType in listOf("tv", "movie")) {
                detail.value = show.copy(mediaType = mediaType)
                waitForIdle()
                onNodeWithText("Want").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `wanting a whole show asks for every episode and opens its library page`() = runUiTest {
        StubServer(routes).use { stub ->
            val viewed = wantShow(stub, pick = "All episodes")

            assertEquals(listOf("tv" to "show-9"), viewed, "the want did not open the show's library page")
            assertTrue(
                stub.requestBodies["/api/v1/admin/tv/shows/show-9/monitor"]?.contains("\"all\"") == true,
                "the show was monitored as ${stub.requestBodies["/api/v1/admin/tv/shows/show-9/monitor"]}",
            )
        }
    }

    /**
     * The one intent nothing can carry across the wait: the show's episodes do not exist
     * when it is added — creation only enqueues the fetch that finds them — and a
     * one-shot want is not recorded anywhere to be re-tried when they arrive. A mode is,
     * so those are the only answers offered here.
     */
    @Test
    fun `a show that is not here yet is not offered a one-shot want`() = runUiTest {
        StubServer(routes).use { stub ->
            openWantPicker(stub)

            onNodeWithText("All episodes").assertIsDisplayed()
            onAllNodesWithText("Just the missing ones, once").assertCountEquals(0)
        }
    }
}
