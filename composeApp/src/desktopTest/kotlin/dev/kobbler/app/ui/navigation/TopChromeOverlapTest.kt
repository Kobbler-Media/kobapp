package dev.kobbler.app.ui.navigation

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.LocalTopChromeOverlap
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.dashboard.ActivityPage
import dev.kobbler.app.ui.dashboard.BooksScreen
import dev.kobbler.app.ui.dashboard.CalendarScreen
import dev.kobbler.app.ui.dashboard.IptvScreen
import dev.kobbler.app.ui.dashboard.LiveSeriesRuleScreen
import dev.kobbler.app.ui.dashboard.LiveTvScreen
import dev.kobbler.app.ui.dashboard.MoviesScreen
import dev.kobbler.app.ui.dashboard.NotificationsScreen
import dev.kobbler.app.ui.dashboard.SettingsScreen
import dev.kobbler.app.ui.dashboard.TvShowsScreen
import dev.kobbler.app.ui.dashboard.WantedScreen
import dev.kobbler.app.ui.dashboard.WantedTab
import dev.kobbler.app.ui.dashboard.home.HomePhone
import dev.kobbler.app.ui.detail.LiveChannelDetailScreen
import dev.kobbler.app.ui.detail.LiveRecordingDetailScreen
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A page scrolls up under the chrome pinned over it — the bar, the status bar, its own
 * header — and off the top of the screen, as its picture does, rather than being cut off at
 * the chrome's lower edge. At rest it still opens below all of it, and a pinned header stays put.
 */
@OptIn(ExperimentalTestApi::class)
class TopChromeOverlapTest {

    private enum class Chrome { Bar, StatusBar }

    private class Case(
        val name: String,
        val header: String,
        val item: String? = null,
        val chrome: Chrome = Chrome.Bar,
        // The control lowest in a header that is itself made of pinned parts.
        val lowestPinned: String? = null,
        val content: @Composable (KobblerApi) -> Unit,
    )

    private val cases = listOf(
        Case("home", "Queued 1") { api ->
            HomePhone(homeData = runBlocking { api.getDiscover() }, api = api, onContinueClick = {}, onRecentClick = {}, onDiscoverItemClick = {})
        },
        Case("movies", "Movies") { api -> MoviesScreen(api = api, formFactor = FormFactor.Phone, onMovieClick = {}) },
        Case("tv", "TV Shows") { api -> TvShowsScreen(api = api, formFactor = FormFactor.Phone, onShowClick = {}) },
        Case("settings", "Settings") { _ -> SettingsScreen(serverUrl = "http://kobbler", onLogout = {}, formFactor = FormFactor.Phone) },
        Case("books", "Books", "Book 1") { api ->
            BooksScreen(api = api, audiobooksOnly = false, formFactor = FormFactor.Phone, onBookClick = {}, onAuthorClick = {}, onSeriesClick = {})
        },
        Case("wanted", "Wanted", "Want 1") { api -> WantedScreen(api = api, formFactor = FormFactor.Phone, initialTab = WantedTab.Wanted) },
        Case("downloads", "Downloads", "Download 1") { api -> WantedScreen(api = api, formFactor = FormFactor.Phone) },
        Case("activity", "Activity", "Watched 1") { api -> ActivityPage(api = api, formFactor = FormFactor.Phone) },
        Case("notifications", "Notifications", "Note 1") { api -> NotificationsScreen(api = api, formFactor = FormFactor.Phone) },
        Case("calendar", "Calendar", "Release 1") { api -> CalendarScreen(api = api, formFactor = FormFactor.Phone) },
        Case("iptv", "Guide", "Iptv 1") { api -> IptvScreen(api = api, formFactor = FormFactor.Phone) },
        Case("livetv", "Guide", "Programme 1", lowestPinned = "Pick a time") { api -> LiveTvScreen(api = api, formFactor = FormFactor.Phone) },
        Case("series-rules", "Series rules", "Rule 1", Chrome.StatusBar) { api -> LiveSeriesRuleScreen(api = api, formFactor = FormFactor.Phone) },
        Case("live-channel", "Channel 1", "Programme 1", Chrome.StatusBar) { api ->
            val row = runBlocking { api.getLiveGuide(channelIds = listOf("c1")) }.single()
            LiveChannelDetailScreen(channel = row.channel, entries = row.entries, api = api, formFactor = FormFactor.Phone)
        },
        Case("live-recording", "Recorded show", chrome = Chrome.StatusBar) { api ->
            LiveRecordingDetailScreen(recording = runBlocking { api.getLiveRecording("r1") }, api = api, formFactor = FormFactor.Phone)
        },
    )

    private fun DesktopComposeUiTest.top(text: String) = onAllNodesWithText(text).onFirst().getUnclippedBoundsInRoot()

    @Test
    fun `phone pages scroll under the chrome pinned over them`() {
        val failures = cases.mapNotNull { case -> runCatching { check(case) }.exceptionOrNull()?.let { "${case.name}: ${it.message?.lineSequence()?.first()}" } }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun check(case: Case) {
        runUiTest(width = 400, height = 800) {
            DataCache.clear()
            StubServer(TabPageStubs.routes).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                setContent {
                    val auth = AuthState(rememberCoroutineScope(), FormFactor.Phone)
                    CompositionLocalProvider(LocalAuthState provides auth) {
                        KobappTheme(formFactor = FormFactor.Phone) {
                            FocusScopeHost(id = case.name) {
                                when (case.chrome) {
                                    Chrome.Bar -> TabScreenWithTopBar(onMenuClick = {}, onSearchClick = {}, formFactor = FormFactor.Phone) {
                                        case.content(api)
                                    }
                                    // Desktop reports no status bar; stand one in.
                                    Chrome.StatusBar -> Box(Modifier.padding(top = StatusBar)) {
                                        CompositionLocalProvider(LocalTopChromeOverlap provides StatusBar) { case.content(api) }
                                    }
                                }
                            }
                        }
                    }
                }
                val wanted = case.item ?: case.header
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(wanted).fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                val chromeBottom = if (case.chrome == Chrome.Bar) onNodeWithContentDescription("Menu").getUnclippedBoundsInRoot().bottom else StatusBar
                val header = top(case.header)
                assertTrue(header.top >= chromeBottom, "${case.name}: '${case.header}' $header starts under the chrome ending at $chromeBottom")
                val pinnedBottom = case.lowestPinned?.let { onNodeWithContentDescription(it).getUnclippedBoundsInRoot() } ?: header
                case.item?.let {
                    val item = top(it)
                    assertTrue(item.top >= pinnedBottom.bottom, "${case.name}: '$it' $item starts under its header $pinnedBottom")
                }

                val scroller = onAllNodes(hasScrollAction() and verticalScroll).onFirst()
                scroller.performTouchInput { swipeUp() }
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
                val scrolled = scroller.getUnclippedBoundsInRoot()
                assertTrue(scrolled.top <= 0.dp, "${case.name}: scrolled list $scrolled is cut off below the top of the screen")
                if (case.item != null) {
                    assertTrue(top(case.header).top == header.top, "${case.name}: the header moved from $header to ${top(case.header)}")
                }
                case.lowestPinned?.let {
                    val after = onNodeWithContentDescription(it).getUnclippedBoundsInRoot()
                    assertTrue(after.top == pinnedBottom.top, "${case.name}: '$it' moved from $pinnedBottom to $after")
                }
            }
        }
    }

    private val desktopTabs = listOf(
        Destination.Tab.Movies to "Movies",
        Destination.Tab.TvShows to "TV Shows",
        Destination.Tab.Books to "Book 1",
        Destination.Tab.Downloads to "Download 1",
        Destination.Tab.Activity to "Watched 1",
        Destination.Tab.Iptv to "Iptv 1",
        Destination.Tab.Settings to "Settings",
    )

    @Test
    fun `desktop tabs run up under the top bar like home`() {
        val failures = desktopTabs.mapNotNull { (tab, text) ->
            runCatching { checkDesktop(tab, text) }.exceptionOrNull()?.let { "${tab.title}: ${it.message?.lineSequence()?.first()}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun checkDesktop(tab: Destination.Tab, text: String) {
        runUiTest(width = 1280, height = 800) {
            DataCache.clear()
            StubServer(TabPageStubs.routes).use { stub ->
                val nav = NavigationState().apply { navigate(tab) }
                setContent {
                    val auth = AuthState(rememberCoroutineScope(), FormFactor.Desktop)
                    CompositionLocalProvider(LocalAuthState provides auth) {
                        KobappTheme(formFactor = FormFactor.Desktop) {
                            DesktopScaffold(
                                nav = nav,
                                navConfig = defaultNavConfig,
                                api = KobblerApi(HttpClient(), stub.url, "token"),
                                formFactor = FormFactor.Desktop,
                                player = null,
                                gps = GlobalPlayerState(),
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                val bar = onNodeWithContentDescription("Notifications").getUnclippedBoundsInRoot()
                val shown = top(text)
                assertTrue(shown.top >= bar.bottom, "'$text' $shown starts under the bar ending at ${bar.bottom}")
                val scroller = onAllNodes(hasScrollAction() and verticalScroll).onFirst().getUnclippedBoundsInRoot()
                assertTrue(scroller.top <= bar.top, "the page $scroller stops below the bar $bar instead of running up under it")
            }
        }
    }

    private val verticalScroll = SemanticsMatcher("scrolls vertically") {
        it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
    }

    private companion object {
        val StatusBar: Dp = 24.dp
    }
}
