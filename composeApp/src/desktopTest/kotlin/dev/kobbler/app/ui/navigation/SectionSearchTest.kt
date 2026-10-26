package dev.kobbler.app.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.dashboard.SearchScope
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScopeStore
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/** Browse sections have no search bar; on a TV their header's search button opens search scoped to them. */
@OptIn(ExperimentalTestApi::class)
class SectionSearchTest {

    private val cases = listOf(
        Triple(Destination.Tab.Movies, "Movies", SearchScope.Movies),
        Triple(Destination.Tab.TvShows, "TV Shows", SearchScope.Tv),
        Triple(Destination.Tab.Books, "Books", SearchScope.Books),
        Triple(Destination.Tab.Audiobooks, "Audiobooks", SearchScope.Books),
        Triple(Destination.Tab.LiveTv, "Live TV", SearchScope.LiveTv),
        Triple(Destination.Tab.Iptv, "IPTV", SearchScope.Iptv),
    )

    private val routes = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> String>(
        "/api/v1/admin/discover" to { _ -> """{"watch_next":[],"recently_added":[],"sections":[]}""" },
        "/api/v1/admin/dashboard" to { _ -> "{}" },
        "/api/v1/admin/movies" to { _ -> """{"items":[{"id":"m1","title":"Grid 1","year":2020}],"total_count":1}""" },
        "/api/v1/admin/tv/shows" to { _ -> """{"items":[{"id":"s1","title":"Show 1","year":2020}],"total_count":1}""" },
        "/api/v1/admin/livetv/tuners" to { _ -> """{"items":[]}""" },
        "/api/v1/admin/livetv/channels" to { _ -> """{"items":[{"id":"c7","lcn":7,"name":"Seven"}],"total":1}""" },
        "/api/v1/admin/livetv/guide" to { _ -> """{"channels":[]}""" },
        "/api/v1/admin/iptv/favourites" to { _ -> "[]" },
        "/api/v1/admin/iptv/groups" to { _ -> "[]" },
        "/api/v1/admin/iptv/channels" to { _ -> """{"items":[{"id":"i1","name":"Sky Sport","category":"Sports"}],"total":1}""" },
    )

    @Test
    fun `a section header search button opens search scoped to that section`() {
        val failures = cases.mapNotNull { (tab, title, scope) ->
            runCatching { check(tab, title, scope) }.exceptionOrNull()?.let { "${tab.title}: ${it.message?.lineSequence()?.first()}" }
        }
        assertEquals(emptyList(), failures)
    }

    private fun check(tab: Destination.Tab, title: String, scope: SearchScope) = runUiTest(width = 1280, height = 720) {
        DataCache.clear()
        StubServer(routes).use { stub ->
            val nav = NavigationState().apply { navigate(tab) }
            setContent {
                val auth = AuthState(rememberCoroutineScope(), FormFactor.TV)
                CompositionLocalProvider(LocalAuthState provides auth, LocalFocusScopeStore provides FocusScopeStore()) {
                    KobappTheme(formFactor = FormFactor.TV) {
                        DesktopScaffold(
                            nav = nav,
                            navConfig = defaultNavConfig,
                            api = KobblerApi(HttpClient(), stub.url, "token"),
                            formFactor = FormFactor.TV,
                            player = null,
                            gps = GlobalPlayerState(),
                        )
                    }
                }
            }
            val button = "Search $title"
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithContentDescription(button).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription(button).performClick()
            waitForIdle()
            assertEquals(true to scope, nav.searchOpen to nav.searchScope)
        }
    }
}
