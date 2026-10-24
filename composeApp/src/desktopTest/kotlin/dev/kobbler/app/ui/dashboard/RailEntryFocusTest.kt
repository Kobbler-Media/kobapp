package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** Stepping off the TV rail into a browse tab lands on its top row, Continue Watching. */
@OptIn(ExperimentalTestApi::class)
class RailEntryFocusTest {

    private var episode = "e1"

    private fun discover() = buildString {
        append("""{"watch_next":[""")
        append(
            listOf(
                """{"media_type":"episode","media_id":"$episode","title":"Watching Show","link":"/tv/s1","progress_percent":40}""",
                """{"media_type":"movie","media_id":"m1","title":"Watching Movie","link":"/movies/m1","progress_percent":40}""",
            ).joinToString(","),
        )
        append("""],"recently_added":[],"sections":[]}""")
    }
    private val dashboard = buildString {
        append("""{"recent_tv":[""")
        append((1..6).joinToString(",") {
            """{"show_id":"rs$it","show_title":"Recent Show $it","season_identifier":"S01","episode_id":"re$it","episode_identifier":"S01E01"}"""
        })
        append("""],"recent_movies":[""")
        append((1..6).joinToString(",") { """{"id":"rm$it","title":"Recent Movie $it","year":2024}""" })
        append("]}")
    }
    private val shows = buildString {
        append("""{"items":[""")
        append((1..20).joinToString(",") { """{"id":"g$it","title":"Grid Show $it"}""" })
        append("""],"total_count":20}""")
    }
    private val movies = buildString {
        append("""{"items":[""")
        append((1..20).joinToString(",") { """{"id":"gm$it","title":"Grid Movie $it","year":2020}""" })
        append("""],"total_count":20}""")
    }

    private fun DesktopComposeUiTest.press(keys: List<Key>) = keys.forEach {
        onRoot().performKeyInput { pressKey(it) }
        mainClock.advanceTimeBy(500)
        waitForIdle()
    }

    private fun DesktopComposeUiTest.press(key: Key) = press(listOf(key))

    private fun DesktopComposeUiTest.focusedHas(text: String): Boolean =
        onAllNodes(isFocused() and (hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))))
            .fetchSemanticsNodes().isNotEmpty()

    private fun DesktopComposeUiTest.anythingFocused(): Boolean = onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()

    private fun DesktopComposeUiTest.railTo(nav: NavigationState, tab: Destination.Tab) {
        press(Key.Back)
        val tabs = defaultNavConfig.sidebarTabs
        val steps = tabs.indexOf(tab) - tabs.indexOf(nav.currentTab)
        press(List(kotlin.math.abs(steps)) { if (steps > 0) Key.DirectionDown else Key.DirectionUp })
        press(Key.DirectionRight)
        waitUntil(timeoutMillis = UiTimeoutMs) { anythingFocused() }
    }

    private fun run(test: DesktopComposeUiTest.(NavigationState) -> Unit) = runUiTest(width = 960, height = 540) {
        DataCache.clear()
        StubServer(
            mapOf(
                "/api/v1/admin/discover" to { _ -> discover() },
                "/api/v1/admin/dashboard" to { _ -> dashboard },
                "/api/v1/admin/tv/shows" to { _ -> shows },
                "/api/v1/admin/movies" to { _ -> movies },
            ),
        ).use { stub ->
            val nav = NavigationState()
            setContent {
                val auth = AuthState(rememberCoroutineScope(), FormFactor.TV)
                CompositionLocalProvider(LocalAuthState provides auth) {
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
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            test(nav)
        }
    }

    @Test
    fun `entering tv shows from the rail focuses continue watching`() = run { nav ->
        railTo(nav, Destination.Tab.TvShows)
        assertTrue(focusedHas("Watching Show"))
    }

    @Test
    fun `coming back from the rail after browsing lower rows still starts at the top`() = run { nav ->
        val visits = listOf(
            Destination.Tab.TvShows to "Watching Show",
            Destination.Tab.Movies to "Watching Movie",
            Destination.Tab.TvShows to "Watching Show",
            Destination.Tab.Movies to "Watching Movie",
        )
        visits.forEachIndexed { i, (tab, watching) ->
            railTo(nav, tab)
            assertTrue(focusedHas(watching), "visit ${i + 1} to $tab")
            press(Key.DirectionDown)
            assertTrue(focusedHas("Recent"), "visit ${i + 1} to $tab moved down")
            episode = "e${i + 2}"
            DataCache.clear()
        }
    }
}
