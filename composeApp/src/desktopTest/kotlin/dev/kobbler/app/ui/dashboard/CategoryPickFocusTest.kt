package dev.kobbler.app.ui.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
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
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** On a remote, picking a category has to leave the D-pad somewhere it can reach the grid from. */
@OptIn(ExperimentalTestApi::class)
class CategoryPickFocusTest {

    private val titles = mapOf(
        "Family" to listOf("Toybox", "Tiny Toons"),
        null to listOf("Toybox", "Tiny Toons", "Gorefest"),
    )

    private fun page(exchange: HttpExchange): String {
        val genre = exchange.requestURI.query.orEmpty().split('&')
            .firstOrNull { it.startsWith("genre=") }?.removePrefix("genre=")
        val items = titles.getValue(genre).joinToString(",") { """{"id":"$it","title":"$it","year":2020}""" }
        return """{"items":[$items]}"""
    }

    private val genres = """{"genres":[{"name":"Family","count":2},{"name":"Horror","count":1}]}"""

    private fun browse(tab: Destination.Tab, test: DesktopComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 960, height = 540) {
            DataCache.clear()
            StubServer(
                mapOf(
                    "/api/v1/admin/movies" to ::page,
                    "/api/v1/admin/tv/shows" to ::page,
                    "/api/v1/admin/movies/genres" to { _ -> genres },
                    "/api/v1/admin/tv/shows/genres" to { _ -> genres },
                    "/api/v1/admin/discover" to { _ -> """{"watch_next":[],"recently_added":[],"sections":[]}""" },
                    "/api/v1/admin/dashboard" to { _ -> "{}" },
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
                runOnIdle { nav.navigate(tab) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Gorefest").fetchSemanticsNodes().isNotEmpty() }
                waitUntil(timeoutMillis = 5_000) { focusedText().isNotEmpty() }
                test()
            }
        }

    private fun DesktopComposeUiTest.press(key: Key) {
        val focused = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
        assertTrue(focused.fetchSemanticsNodes().isNotEmpty(), "nothing is focused, so $key goes nowhere")
        // The picker is a dialog, a root of its own: the key goes to whichever root holds focus.
        focused.onLast().performKeyInput { pressKey(key) }
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }

    private fun DesktopComposeUiTest.focusedText(): List<String> {
        fun texts(node: SemanticsNode): List<String> =
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } + node.children.flatMap(::texts)
        return onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
            .fetchSemanticsNodes()
            .flatMap(::texts)
    }

    private fun DesktopComposeUiTest.stepUntilFocused(key: Key, text: String, max: Int = 8) {
        repeat(max) {
            if (text in focusedText()) return
            press(key)
        }
        assertTrue(text in focusedText(), "never reached \"$text\" with $key; focus is on ${focusedText()}")
    }

    private fun pickingACategoryLeavesTheGridReachable(tab: Destination.Tab) = browse(tab) {
        stepUntilFocused(Key.DirectionUp, "All library")
        stepUntilFocused(Key.DirectionRight, "Categories")
        press(Key.Enter)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Horror").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        stepUntilFocused(Key.DirectionRight, "Family")
        press(Key.Enter)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Gorefest").fetchSemanticsNodes().isEmpty() }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        repeat(3) {
            if (focusedText().any { it == "Toybox" || it == "Tiny Toons" }) return@browse
            press(Key.DirectionDown)
        }
        assertTrue(
            focusedText().any { it == "Toybox" || it == "Tiny Toons" },
            "$tab: after picking a category the D-pad cannot reach the grid; focus is on ${focusedText()}",
        )
    }

    @Test
    fun `movies grid is reachable after picking a category`() = pickingACategoryLeavesTheGridReachable(Destination.Tab.Movies)

    @Test
    fun `tv shows grid is reachable after picking a category`() = pickingACategoryLeavesTheGridReachable(Destination.Tab.TvShows)
}
