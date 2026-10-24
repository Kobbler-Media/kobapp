package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.navigation.DesktopScaffold
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.navigation.NavigationState
import dev.kobbler.app.ui.navigation.defaultNavConfig
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScopeStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A show the library does not have, opened from Discover, laid out as its library page
 * would be: one action to start watching, and the season it starts in already open.
 */
@OptIn(ExperimentalTestApi::class)
class DiscoverShowPageTest {

    private val routes = mapOf<String, (HttpExchange) -> String>(
        "/api/v1/admin/discover/tv/tmdb/97951" to { _ ->
            """{"media_type":"tv","provider":"tmdb","details":{"title":"Mayor of Kingstown","year":2021,
               "identifiers":{"tmdb_id":"97951","imdb_id":"tt4489382"},
               "seasons":[{"season_number":0,"title":"Specials","episode_count":2},
                          {"season_number":1,"title":"Season 1","episode_count":10},
                          {"season_number":2,"title":"Season 2","episode_count":10}]}}"""
        },
        "/api/v1/admin/discover/tv/tmdb/97951/season/1" to { _ ->
            """{"episodes":[{"episode_number":1,"title":"The Mayor","description":"Mike runs the town."},
                            {"episode_number":2,"title":"The Price of Doing Business"}]}"""
        },
        "/api/v1/admin/stream/search" to { _ -> """{"options":[]}""" },
        "/api/v1/library/stats" to { _ -> """{"kobload_configured":true}""" },
    )

    private fun discoverPage(test: DesktopComposeUiTest.(StubServer) -> Unit) =
        runUiTest(width = 1280, height = 720) {
            DataCache.clear()
            StubServer(routes).use { stub ->
                val nav = NavigationState()
                val api = KobblerApi(HttpClient(), stub.url, "token")
                // Probed before the page opens, as the app does at sign-in: a probe landing
                // after entry swaps the primary action out from under the focus it was given.
                runBlocking { api.checkStreamingAvailability() }
                setContent {
                    val auth = AuthState(rememberCoroutineScope(), FormFactor.TV)
                    CompositionLocalProvider(LocalAuthState provides auth, LocalFocusScopeStore provides FocusScopeStore()) {
                        KobappTheme(formFactor = FormFactor.TV) {
                            DesktopScaffold(
                                nav = nav,
                                navConfig = defaultNavConfig,
                                api = api,
                                formFactor = FormFactor.TV,
                                player = null,
                                gps = GlobalPlayerState(),
                            )
                        }
                    }
                }
                mainClock.advanceTimeBy(1_000)
                runOnIdle { nav.navigate(Destination.DiscoverItemDetail("tv", "tmdb", "97951")) }
                waitUntil(timeoutMillis = UiTimeoutMs) { focusedText() != null }
                test(stub)
            }
        }

    private fun DesktopComposeUiTest.focusedText(): String? =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
            .fetchSemanticsNodes().lastOrNull()
            ?.let { node ->
                node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString(" ")
                    ?: node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.joinToString(" ")
                    ?: ""
            }

    @Test
    fun `entry focus is the stream action, naming the first episode`() = discoverPage {
        assertEquals("Stream S01 E01", focusedText())
    }

    @Test
    fun `the first season is open without asking`() = discoverPage {
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("The Mayor").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("The Mayor").assertIsDisplayed()
        onNodeWithText("Mike runs the town.").assertIsDisplayed()
    }

    @Test
    fun `the stream action searches for that episode`() = discoverPage { stub ->
        onNodeWithText("S01 E01").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { "/api/v1/admin/stream/search" in stub.requestBodies }
        val search = Json.parseToJsonElement(stub.requestBodies.getValue("/api/v1/admin/stream/search")).jsonObject
        assertEquals(1, search["season"]?.jsonPrimitive?.int)
        assertEquals(1, search["episode"]?.jsonPrimitive?.int)
        assertTrue(search["tmdb_id"] != null)
    }
}
