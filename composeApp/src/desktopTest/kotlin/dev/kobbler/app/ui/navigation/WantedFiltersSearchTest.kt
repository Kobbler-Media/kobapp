package dev.kobbler.app.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The Downloads page's pane and filters are where the viewer left them after a trip to search. */
@OptIn(ExperimentalTestApi::class)
class WantedFiltersSearchTest {

    private val routes = mapOf<String, (HttpExchange) -> String>(
        "/api/v1/admin/downloads" to { exchange ->
            val name = if (exchange.requestURI.query.orEmpty().contains("failed")) "Broken Grab" else "Running Grab"
            """{"items":[{"id":"$name","name":"$name","status":"downloading"}]}"""
        },
        "/api/v1/admin/wanted" to { _ -> """{"items":[{"id":"b1","title":"Wanted Book","want_status":"wanted"}]}""" },
        "/api/v1/admin/movies" to { _ -> """{"items":[{"id":"m1","title":"Wanted Film","year":2020}]}""" },
        "/api/v1/admin/tv/shows" to { _ -> """{"items":[{"id":"s1","title":"Wanted Series","year":2020}]}""" },
    )

    private fun downloadsPage(test: DesktopComposeUiTest.(NavigationState) -> Unit) = runUiTest(width = 1280, height = 900) {
        DataCache.clear()
        StubServer(routes).use { stub ->
            val nav = NavigationState().apply { navigate(Destination.Tab.Downloads) }
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
            waitUntil(timeoutMillis = UiTimeoutMs) { has("Running Grab") }
            test(nav)
        }
    }

    private fun DesktopComposeUiTest.has(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun DesktopComposeUiTest.pick(label: String) = onAllNodesWithText(label).onFirst().performClick()

    private fun DesktopComposeUiTest.searchAndBack(nav: NavigationState, shown: String) {
        runOnIdle { nav.openSearch() }
        waitUntil("search replaced the page", timeoutMillis = UiTimeoutMs) { !has(shown) }
        runOnIdle { nav.searchOpen = false }
        waitUntil("the page came back", timeoutMillis = UiTimeoutMs) { has(shown) || has("Running Grab") }
        waitForIdle()
    }

    @Test
    fun `the downloads filter survives search`() = downloadsPage { nav ->
        pick("Failed")
        waitUntil(timeoutMillis = UiTimeoutMs) { has("Broken Grab") }
        searchAndBack(nav, "Broken Grab")
        assertFalse(has("Running Grab"), "the filter fell back to All")
    }

    @Test
    fun `the wanted pane and its filter survive search`() = downloadsPage { nav ->
        pick("Wanted")
        waitUntil(timeoutMillis = UiTimeoutMs) { has("Wanted Book") && has("Wanted Series") }
        pick("TV")
        waitUntil(timeoutMillis = UiTimeoutMs) { !has("Wanted Book") }
        searchAndBack(nav, "Wanted Series")
        assertEquals(Destination.Tab.Downloads, nav.current)
        assertFalse(has("Running Grab"), "the page fell back to Downloads")
        assertFalse(has("Wanted Book"), "the filter fell back to All")
    }
}
