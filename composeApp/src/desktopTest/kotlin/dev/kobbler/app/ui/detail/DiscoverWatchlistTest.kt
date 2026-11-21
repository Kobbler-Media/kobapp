package dev.kobbler.app.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A title to stream some other time: on the list, without asking the library to fetch any of it. */
@OptIn(ExperimentalTestApi::class)
class DiscoverWatchlistTest {

    private val show = DiscoverDetail(
        mediaType = "tv", provider = "tmdb", title = "Some Day", year = 2026,
        description = null, posterUrl = "", backdropUrl = null, genres = emptyList(),
        rating = null, cast = emptyList(), network = null, runtime = null,
        authors = emptyList(), libraryId = null, wantStatus = null, isInLibrary = false,
        identifiers = mapOf("tmdb_id" to "77"), providerId = "77",
    )

    private val routes = mapOf(
        "/api/v1/admin/discover/watchlist" to { _: HttpExchange -> """{"media_type":"show","media_id":"show-77"}""" },
    )

    private fun DesktopComposeUiTest.showPage(stub: StubServer, detail: DiscoverDetail): List<Pair<String, String>> {
        val api = KobblerApi(HttpClient(), stub.url, "token")
        val viewed = mutableListOf<Pair<String, String>>()
        val state = mutableStateOf(detail)
        setContent {
            Box(Modifier.size(1280.dp, 720.dp)) {
                KobappTheme(formFactor = FormFactor.TV) {
                    DiscoverDetailScreen(
                        detail = state.value,
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

    @Test
    fun `adding a discover show to the list opens its library page without wanting it`() = runUiTest {
        StubServer(routes).use { stub ->
            val viewed = showPage(stub, show)
            performAction("Add to my list")
            waitUntil(timeoutMillis = UiTimeoutMs) { viewed.isNotEmpty() }

            assertEquals(listOf("tv" to "show-77"), viewed)
            val body = stub.requestBodies["/api/v1/admin/discover/watchlist"].orEmpty()
            assertTrue("\"media_type\":\"tv\"" in body && "\"provider_id\":\"77\"" in body, "sent $body")
            assertTrue(stub.requestBodies.keys.none { "monitor" in it || it.endsWith("/want") }, "asked for ${stub.requestBodies.keys}")
        }
    }

    @Test
    fun `a discover movie is offered the list too`() = runUiTest {
        StubServer(routes).use { stub ->
            showPage(stub, show.copy(mediaType = "movie"))
            assertOfferedOnce("Add to my list")
        }
    }

    @Test
    fun `a book is not offered the list`() = runUiTest {
        StubServer(routes).use { stub ->
            showPage(stub, show.copy(mediaType = "book"))
            openOverflowIfAny()
            assertFalse(rowHas("Add to my list"))
        }
    }
}
