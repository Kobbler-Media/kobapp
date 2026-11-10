package dev.kobbler.app.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScopeStore
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertFalse

/** Continuing is a narrower question than the rails answer, and a trip to search keeps it asked. */
@OptIn(ExperimentalTestApi::class)
class TvContinuingTest {

    private val routes = mapOf<String, (HttpExchange) -> String>(
        "/api/v1/admin/discover" to { _ ->
            """{"watch_next":[{"media_type":"episode","media_id":"e1","title":"Halfway Show","link":"/tv/s9","progress_percent":40}],"recently_added":[],"sections":[]}"""
        },
        "/api/v1/admin/dashboard" to { _ ->
            """{"recent_tv":[{"show_id":"s8","show_title":"Fresh Show","season_identifier":"S01","episode_id":"e8","episode_identifier":"S01E01"}]}"""
        },
        "/api/v1/admin/tv/shows" to { exchange ->
            val title = if (exchange.requestURI.query.orEmpty().contains("status=continuing")) "Carousel" else "Ended Show"
            """{"items":[{"id":"$title","title":"$title","year":2020}],"total_count":1}"""
        },
    )

    private fun tvShows(formFactor: FormFactor = FormFactor.TV, test: DesktopComposeUiTest.(NavigationState) -> Unit) = runUiTest(width = 1280, height = 900) {
        DataCache.clear()
        StubServer(routes).use { stub ->
            val nav = NavigationState().apply { navigate(Destination.Tab.TvShows) }
            setContent {
                val auth = AuthState(rememberCoroutineScope(), formFactor)
                CompositionLocalProvider(LocalAuthState provides auth, LocalFocusScopeStore provides FocusScopeStore()) {
                    KobappTheme(formFactor = formFactor) {
                        DesktopScaffold(
                            nav = nav,
                            navConfig = defaultNavConfig,
                            api = KobblerApi(HttpClient(), stub.url, "token"),
                            formFactor = formFactor,
                            player = null,
                            gps = GlobalPlayerState(),
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = UiTimeoutMs) { has("Ended Show") && has("Continue Watching") }
            onNodeWithText("Continuing").performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { has("Carousel") }
            test(nav)
        }
    }

    private fun DesktopComposeUiTest.has(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `continuing shows have no continue watching or recently added rails`() = tvShows {
        waitForIdle()
        assertFalse(has("Continue Watching"), "Continue Watching shown under Continuing")
        assertFalse(has("Recently Added"), "Recently Added shown under Continuing")
    }

    @Test
    fun `back from search keeps the continuing filter`() {
        for (formFactor in listOf(FormFactor.TV, FormFactor.Desktop)) backFromSearch(formFactor)
    }

    private fun backFromSearch(formFactor: FormFactor) = tvShows(formFactor) { nav ->
        runOnIdle { nav.openSearch() }
        waitUntil("search replaced the page", timeoutMillis = UiTimeoutMs) { !has("Carousel") }
        runOnIdle { nav.searchOpen = false }
        waitUntil("the page came back", timeoutMillis = UiTimeoutMs) { has("Carousel") || has("Ended Show") }
        waitForIdle()
        assertFalse(has("Ended Show") || has("All shows"), "$formFactor: the filter fell back to All library")
    }
}
