package dev.kobbler.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.requestFocus
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.assertRoomBelowLastDownStop
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.dashboard.ActivityPage
import dev.kobbler.app.ui.dashboard.BooksScreen
import dev.kobbler.app.ui.dashboard.CalendarScreen
import dev.kobbler.app.ui.dashboard.IptvScreen
import dev.kobbler.app.ui.dashboard.LiveSeriesRuleScreen
import dev.kobbler.app.ui.dashboard.MoviesScreen
import dev.kobbler.app.ui.dashboard.NotificationsScreen
import dev.kobbler.app.ui.dashboard.SettingsScreen
import dev.kobbler.app.ui.dashboard.TvShowsScreen
import dev.kobbler.app.ui.dashboard.WantedScreen
import dev.kobbler.app.ui.dashboard.WantedTab
import dev.kobbler.app.ui.dashboard.WatchHistoryScreen
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** On a TV, the remote walks to the bottom of every page and the last ring is drawn whole. */
@OptIn(ExperimentalTestApi::class)
class PageBottomRoomTest {

    private class Case(val name: String, val item: String, val content: @Composable (KobblerApi) -> Unit)

    private val tv = FormFactor.TV

    private val cases = listOf(
        Case("movies", "Film 1") { api -> MoviesScreen(api = api, formFactor = tv, onMovieClick = {}) },
        Case("tv", "Show 1") { api -> TvShowsScreen(api = api, formFactor = tv, onShowClick = {}) },
        Case("books", "Book 1") { api -> BooksScreen(api = api, audiobooksOnly = false, formFactor = tv, onBookClick = {}, onAuthorClick = {}, onSeriesClick = {}) },
        Case("wanted", "Want 1") { api -> WantedScreen(api = api, formFactor = tv, initialTab = WantedTab.Wanted) },
        Case("downloads", "Download 1") { api -> WantedScreen(api = api, formFactor = tv) },
        Case("activity", "Watched 1") { api -> ActivityPage(api = api, formFactor = tv) },
        Case("notifications", "Note 1") { api -> NotificationsScreen(api = api, formFactor = tv) },
        Case("calendar", "Release 1") { api -> CalendarScreen(api = api, formFactor = tv) },
        Case("iptv", "Iptv 1") { api -> IptvScreen(api = api, formFactor = tv) },
        Case("series-rules", "Rule 1") { api -> LiveSeriesRuleScreen(api = api, formFactor = tv) },
        Case("watch-history", "Seen 1") { api -> WatchHistoryScreen(api = api, onBack = {}) },
        Case("settings", "Settings") { _ -> SettingsScreen(serverUrl = "http://kobbler", onLogout = {}, formFactor = tv) },
    )

    @Test
    fun `the last focus stop on every tv page has room for its ring`() {
        val failures = cases.mapNotNull { case ->
            runCatching { check(case) }.exceptionOrNull()?.let { "${case.name}: ${it.message?.lineSequence()?.first()}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun check(case: Case) = runUiTest(width = 1280, height = 720) {
        DataCache.clear()
        StubServer(TabPageStubs.routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            var focus: FocusManager? = null
            setContent {
                val auth = AuthState(rememberCoroutineScope(), tv)
                CompositionLocalProvider(LocalAuthState provides auth) {
                    KobappTheme(formFactor = tv) {
                        focus = LocalFocusManager.current
                        FocusScopeHost(id = case.name) { case.content(api) }
                    }
                }
            }
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(case.item).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            if (onAllNodes(isFocused()).fetchSemanticsNodes().isEmpty()) onAllNodesWithText(case.item).onFirst().requestFocus()
            assertRoomBelowLastDownStop({ focus!! }, case.name)
        }
    }
}
