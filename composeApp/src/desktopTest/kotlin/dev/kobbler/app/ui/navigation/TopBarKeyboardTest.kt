package dev.kobbler.app.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
import kotlin.test.assertTrue

/** Arrow keys reach every top bar control from the page under it, and come back down again. */
@OptIn(ExperimentalTestApi::class)
class TopBarKeyboardTest {

    private val barLabels = setOf("Search library & Discover", "Notifications", "Fullscreen", "Cast")

    private val movies = (1..30).joinToString(",", """{"items":[""", """],"total_count":30}""") {
        """{"id":"m$it","title":"Movie $it","year":2020}"""
    }

    private val routes = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> String>(
        "/api/v1/admin/discover" to { _ ->
            """{"watch_next":[{"media_type":"movie","media_id":"m1","title":"Queued 1","link":"/movies/m1","progress_percent":40}],""" +
                """"recently_added":[{"item_type":"movie","id":"r1","title":"Recent 1","subtitle":"2024","link":"/movies/r1"}],"sections":[]}"""
        },
        "/api/v1/admin/dashboard" to { _ -> """{"recent_movies":[]}""" },
        "/api/v1/admin/movies" to { _ -> movies },
        "/api/v1/admin/movies/available-letters" to { _ -> """{"letters":["M"]}""" },
    )

    private fun onTab(tab: Destination.Tab, ready: String, test: DesktopComposeUiTest.() -> Unit) = runUiTest(width = 1280, height = 800) {
        DataCache.clear()
        StubServer(routes).use { stub ->
            val nav = NavigationState()
            setContent {
                val auth = AuthState(rememberCoroutineScope(), FormFactor.Desktop)
                CompositionLocalProvider(LocalAuthState provides auth, LocalFocusScopeStore provides FocusScopeStore()) {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        DesktopScaffold(
                            nav = nav,
                            navConfig = defaultNavConfig,
                            api = KobblerApi(HttpClient(), stub.url, "token"),
                            formFactor = FormFactor.Desktop,
                            player = null,
                            gps = GlobalPlayerState(),
                            onToggleFullscreen = {},
                        )
                    }
                }
            }
            mainClock.advanceTimeBy(1_000)
            runOnIdle { nav.navigate(tab) }
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(ready).fetchSemanticsNodes().isNotEmpty() }
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            test()
        }
    }

    private fun DesktopComposeUiTest.focusedLabels(): List<String> {
        fun SemanticsNode.labels(): List<String> =
            (config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) +
                (config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList()) +
                children.flatMap { it.labels() }
        return onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()?.labels() ?: emptyList()
    }

    private fun DesktopComposeUiTest.inBar() = focusedLabels().any { it in barLabels }

    private fun DesktopComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        mainClock.advanceTimeBy(200)
        waitForIdle()
    }

    private fun DesktopComposeUiTest.upIntoBar(): List<String> {
        var left = focusedLabels()
        repeat(10) {
            press(Key.DirectionUp)
            if (inBar()) return left
            left = focusedLabels()
        }
        error("Up never reached the top bar; on $left")
    }

    private fun DesktopComposeUiTest.assertBarRoundTrips() {
        if (focusedLabels().isEmpty()) press(Key.DirectionDown)
        assertTrue(focusedLabels().isNotEmpty(), "nothing on the page took focus")
        assertTrue(!inBar(), "the page's entry focus landed in the bar")
        val page = upIntoBar()
        val controls = mutableListOf(focusedLabels())
        while (true) {
            press(Key.DirectionRight)
            assertTrue(inBar(), "Right from ${controls.last()} left the bar for ${focusedLabels()}")
            if (focusedLabels() == controls.last()) break
            controls += focusedLabels()
        }
        assertTrue(controls.any { "Fullscreen" in it }, "Right never reached Fullscreen; saw $controls")
        for (control in controls.indices) {
            repeat(controls.size - 1 - control) { press(Key.DirectionLeft) }
            press(Key.DirectionDown)
            assertEquals(page, focusedLabels(), "Down from ${controls[control]} did not return to the page")
            assertEquals(page, upIntoBar())
            repeat(controls.size) { press(Key.DirectionRight) }
        }
    }

    @Test
    fun `toggling fullscreen from the bar hands focus back to the page`() = onTab(Destination.Tab.Movies, "Movie 1") {
        val page = upIntoBar()
        repeat(4) { press(Key.DirectionRight) }
        assertTrue("Fullscreen" in focusedLabels())
        press(Key.Enter)
        assertEquals(page, focusedLabels())
    }

    @Test
    fun `down moves normally after search opened from the bar is closed`() = onTab(Destination.Tab.Movies, "Movie 1") {
        upIntoBar()
        assertTrue("Search" in focusedLabels(), "Up landed on ${focusedLabels()}")
        press(Key.Enter)
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Search library & Discover").fetchSemanticsNodes().isEmpty() }
        press(Key.Escape)
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Search library & Discover").fetchSemanticsNodes().isNotEmpty() }
        if (focusedLabels().isEmpty() || inBar()) press(Key.DirectionDown)
        val before = focusedLabels()
        press(Key.DirectionDown)
        assertTrue(focusedLabels() != before && !inBar(), "Down from $before went to ${focusedLabels()}")
    }

    @Test
    fun `a page's top bar is reachable and returns to the page`() = onTab(Destination.Tab.Movies, "Movie 1") {
        assertBarRoundTrips()
    }

    @Test
    fun `the bar over home's hero is reachable and returns to the page`() = onTab(Destination.Tab.Home, "Queued 1") {
        assertBarRoundTrips()
    }
}
