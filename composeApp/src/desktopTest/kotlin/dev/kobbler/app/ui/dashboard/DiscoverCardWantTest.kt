package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.ForYouItem
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.dashboard.home.ForYouCard
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test

/** Wanting from a card's menu marks the card itself, since nothing else on Home answers the tap. */
@OptIn(ExperimentalTestApi::class)
class DiscoverCardWantTest {

    private val item = DiscoverItem(
        provider = "tmdb", providerId = "603", mediaType = "movie", title = "The Matrix", year = 1999, description = null,
        posterUrl = "", backdropUrl = null, genres = emptyList(), rating = null, ratingCount = null, authors = emptyList(),
        libraryId = null, wantStatus = null, isInLibrary = false,
    )

    private val detail = "/api/v1/admin/discover/movie/tmdb/603" to { _: HttpExchange ->
        """{"media_type":"movie","provider":"tmdb","details":{"title":"The Matrix","year":1999,"identifiers":{"tmdb_id":"603"}}}"""
    }

    private fun card(routes: Map<String, (HttpExchange) -> String>, test: DesktopComposeUiTest.(StubServer) -> Unit) =
        runUiTest(width = 600, height = 600) {
            StubServer(routes).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                setContent {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        ForYouCard(entry = ForYouItem(item, because = emptyList(), seeds = emptyList()), api = api, onClick = {})
                    }
                }
                test(stub)
            }
        }

    private fun DesktopComposeUiTest.hold() {
        onAllNodesWithText("The Matrix").onFirst().performTouchInput { longClick() }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.wanted() = onAllNodesWithContentDescription("Wanted").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `wanting from the menu marks the card wanted and stops offering it`() = card(
        mapOf(
            detail,
            "/api/v1/admin/movies" to { _ -> """{"id":"m9"}""" },
            "/api/v1/admin/movies/m9/want" to { _ -> "{}" },
        ),
    ) { stub ->
        hold()
        onNodeWithText("Want").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { "/api/v1/admin/movies/m9/want" in stub.requestBodies }
        waitForIdle()
        kotlin.test.assertTrue(wanted(), "the card shows it wanted")
        hold()
        kotlin.test.assertTrue(onAllNodesWithText("Want").fetchSemanticsNodes().isEmpty(), "Want is no longer offered")
    }

    @Test
    fun `a want the server refuses leaves the card wantable`() = card(mapOf(detail)) { stub ->
        hold()
        onNodeWithText("Want").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { "/api/v1/admin/movies" in stub.requestBodies }
        waitUntil(timeoutMillis = UiTimeoutMs) { !wanted() }
        hold()
        onNodeWithText("Want").assertExists()
    }

    @Test
    fun `adding to my list from the menu puts it on the list without wanting it`() = card(
        mapOf(
            detail,
            "/api/v1/admin/discover/watchlist" to { _ -> """{"media_type":"movie","media_id":"m9"}""" },
        ),
    ) { stub ->
        hold()
        onNodeWithText("Add to my list").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { "/api/v1/admin/discover/watchlist" in stub.requestBodies }
        waitForIdle()
        kotlin.test.assertTrue("/api/v1/admin/movies" !in stub.requestBodies, "the list add also created a want")
        kotlin.test.assertTrue(!wanted(), "the card claims it is wanted")
    }
}
