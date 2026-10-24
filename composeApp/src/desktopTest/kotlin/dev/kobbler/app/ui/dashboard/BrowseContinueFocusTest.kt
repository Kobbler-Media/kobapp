package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.components.FocusEdgeMargin
import dev.kobbler.app.ui.navigation.DesktopScaffold
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.navigation.NavigationState
import dev.kobbler.app.ui.navigation.defaultNavConfig
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** Stepping around a browse page from its Continue Watching row keeps the focused card on screen. */
@OptIn(ExperimentalTestApi::class)
class BrowseContinueFocusTest {

    private val discover = buildString {
        append("""{"watch_next":[""")
        append((1..10).joinToString(",") {
            """{"media_type":"movie","media_id":"w$it","title":"Watching $it","link":"/movies/w$it","progress_percent":40}"""
        })
        append("""],"recently_added":[],"sections":[]}""")
    }
    private val dashboard = buildString {
        append("""{"recent_movies":[""")
        append((1..10).joinToString(",") { """{"id":"r$it","title":"Recent $it","year":2024}""" })
        append("]}")
    }
    private val movies = buildString {
        append("""{"items":[""")
        append((1..40).joinToString(",") { """{"id":"g$it","title":"Grid $it","year":2020}""" })
        append("""],"total_count":40}""")
    }

    private fun browse(formFactor: FormFactor, width: Int, height: Int, test: DesktopComposeUiTest.() -> Unit) =
        runUiTest(width = width, height = height) {
            DataCache.clear()
            StubServer(
                mapOf(
                    "/api/v1/admin/discover" to { _ -> discover },
                    "/api/v1/admin/dashboard" to { _ -> dashboard },
                    "/api/v1/admin/movies" to { _ -> movies },
                    "/api/v1/admin/movies/available-letters" to { _ -> """{"letters":["G","R","W"]}""" },
                ),
            ).use { stub ->
                val nav = NavigationState()
                setContent {
                    val auth = AuthState(rememberCoroutineScope(), formFactor)
                    CompositionLocalProvider(LocalAuthState provides auth) {
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
                mainClock.advanceTimeBy(1_000)
                runOnIdle { nav.navigate(Destination.Tab.Movies) }
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Watching 1").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                test()
            }
        }

    private fun keepsFocusOnScreen(formFactor: FormFactor, width: Int, height: Int) = browse(formFactor, width, height) {
        waitUntil(timeoutMillis = UiTimeoutMs) { focusedBounds() != null }
        val keys = List(4) { Key.DirectionRight } + List(2) { Key.DirectionDown } + List(2) { Key.DirectionUp } + List(4) { Key.DirectionLeft }
        keys.forEachIndexed { step, key ->
            onRoot().performKeyInput { pressKey(key) }
            mainClock.advanceTimeBy(600)
            waitForIdle()
            val bounds = focusedBounds()!!
            assertTrue(
                bounds.left >= 0.dp && bounds.right <= width.dp && bounds.top >= 0.dp && bounds.bottom <= height.dp,
                "$formFactor step ${step + 1} ($key): focused card at $bounds is off a ${width}x$height screen",
            )
        }
    }

    @Test
    fun `tv keeps focus on screen in every direction`() = keepsFocusOnScreen(FormFactor.TV, 960, 540)

    @Test
    fun `desktop keeps focus on screen in every direction`() = keepsFocusOnScreen(FormFactor.Desktop, 1280, 800)

    /** The row clips what it draws, and the focus ring and scale-up are drawn outside the card. */
    @Test
    fun `the first continue watching card has room for its focus ring`() {
        for ((formFactor, width, height) in listOf(Triple(FormFactor.TV, 960, 540), Triple(FormFactor.Desktop, 1280, 800))) {
            browse(formFactor, width, height) {
                waitUntil(timeoutMillis = UiTimeoutMs) { focusedBounds() != null }
                val card = focusedBounds()!!
                val row = onAllNodes(hasScrollAction() and hasAnyDescendant(hasText("Watching 1")))
                    .fetchSemanticsNodes().last().boundsInRoot.let { with(density) { it.left.toDp() } }
                assertTrue(
                    card.left - row >= FocusEdgeMargin,
                    "$formFactor: the row clips ${card.left - row} left of its first card, under the $FocusEdgeMargin a ring needs",
                )
            }
        }
    }

    /** Rails scroll out to the physical edge, under the A-Z bar, as Home's do. */
    @Test
    fun `rails run to the right edge of the screen`() {
        for ((formFactor, width, height) in listOf(Triple(FormFactor.TV, 960, 540), Triple(FormFactor.Desktop, 1280, 800))) {
            browse(formFactor, width, height) {
                waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("A").fetchSemanticsNodes().isEmpty() && onAllNodesWithText("G").fetchSemanticsNodes().isNotEmpty() }
                for (title in listOf("Watching 1", "Recent 1")) {
                    val row = onAllNodes(hasScrollAction() and hasAnyDescendant(hasText(title)))
                        .fetchSemanticsNodes().last().boundsInRoot.let { with(density) { it.right.toDp() } }
                    assertTrue(row >= width.dp - 1.dp, "$formFactor: the $title row stops at $row of $width")
                }
            }
        }
    }

    /** Rails sit as close together as Home's: the grid's own spacing is the gap, not twice it. */
    @Test
    fun `rails are not padded apart`() = browse(FormFactor.TV, 960, 540) {
        waitUntil(timeoutMillis = UiTimeoutMs) { focusedBounds() != null }
        val card = onAllNodes(hasText("Watching 1")).fetchSemanticsNodes().first().boundsInRoot
        val heading = onAllNodes(hasText("Recently Added")).fetchSemanticsNodes().first().boundsInRoot
        val gap = with(density) { (heading.top - card.bottom).toDp() }
        assertTrue(gap <= 32.dp, "Recently Added starts $gap below the Continue Watching cards")
    }

    private fun DesktopComposeUiTest.focusedBounds(): DpRect? =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
            .fetchSemanticsNodes().lastOrNull()?.boundsInRoot?.let { r ->
                with(density) { DpRect(r.left.toDp(), r.top.toDp(), r.right.toDp(), r.bottom.toDp()) }
            }
}
