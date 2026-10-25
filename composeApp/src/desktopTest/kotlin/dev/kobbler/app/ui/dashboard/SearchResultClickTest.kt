package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.SearchResult
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SearchResultClickTest {

    private val routes = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> String>(
        "/api/v1/admin/search" to { _ ->
            """{"top_hits":[{"type":"tv_season","id":"season-4","show_id":"show-raymond","show_title":"Everybody Loves Raymond","display_title":"Everybody Loves Raymond Season 4","season_identifier":"4","season_code":"S04"}],"remaining":[]}"""
        },
    )

    @Test
    fun `a season result names the show it belongs to`() = runUiTest(width = 1280, height = 900) {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val clicked = mutableListOf<SearchResult>()
            setContent {
                KobappTheme(formFactor = FormFactor.TV) {
                    SearchOverlay(
                        api = api,
                        scope = SearchScope.All,
                        scopes = SearchScope.entries,
                        onScopeChange = {},
                        query = "raymond",
                        onQueryChange = {},
                        onDismiss = {},
                        onResultClick = { clicked += it },
                        formFactor = FormFactor.TV,
                        autoFocusQuery = false,
                    )
                }
            }
            val title = "Everybody Loves Raymond Season 4"
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(title).performClick()
            waitForIdle()
            assertEquals(listOf("show-raymond" to "season-4"), clicked.map { it.showId to it.id })
        }
    }
}
