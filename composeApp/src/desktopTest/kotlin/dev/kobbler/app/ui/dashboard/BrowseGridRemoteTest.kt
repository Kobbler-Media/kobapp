package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
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
import dev.kobbler.app.ui.navigation.DesktopScaffold
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.navigation.NavigationState
import dev.kobbler.app.ui.navigation.defaultNavConfig
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScopeStore
import io.ktor.client.HttpClient
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Walking the movie grid with a remote, as an Android TV runs it: Compose gives any
 * leanback device a pivot bring-into-view that scrolls a focused child 30% down its
 * scroller, which the desktop has to be handed explicitly.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class BrowseGridRemoteTest {

    private val tvPivot = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            val childSize = abs(size)
            val target = 0.3f * containerSize
            val fits = childSize <= containerSize && containerSize - target < childSize
            return offset - if (fits) containerSize - childSize else target
        }
    }

    private val letters = ('A'..'Z').map { it.toString() }
    private val titles = letters.flatMap { l -> (1..6).map { "${l}title $it" } }
    private val movies = titles.joinToString(",", """{"items":[""", """],"total_count":${titles.size}}""") {
        """{"id":"${it.replace(' ', '-')}","title":"$it","year":2020}"""
    }

    private fun onTvGrid(test: DesktopComposeUiTest.() -> Unit) = runUiTest(width = 960, height = 540) {
        DataCache.clear()
        StubServer(
            mapOf(
                "/api/v1/admin/discover" to { _ -> """{"watch_next":[],"recently_added":[],"sections":[]}""" },
                "/api/v1/admin/dashboard" to { _ -> """{"recent_movies":[]}""" },
                "/api/v1/admin/movies" to { _ -> movies },
                "/api/v1/admin/movies/available-letters" to { _ -> letters.joinToString(",", """{"letters":[""", "]}") { "\"$it\"" } },
                "/api/v1/admin/movies/first-by-letter" to { ex ->
                    val letter = ex.requestURI.query.substringAfter("letter=")
                    """{"id":"${letter}title-1","title":"${letter}title 1"}"""
                },
            ),
        ).use { stub ->
            val nav = NavigationState()
            setContent {
                val auth = AuthState(rememberCoroutineScope(), FormFactor.TV)
                CompositionLocalProvider(
                    LocalAuthState provides auth,
                    LocalFocusScopeStore provides FocusScopeStore(),
                    LocalBringIntoViewSpec provides tvPivot,
                ) {
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
            runOnIdle { nav.navigate(Destination.Tab.Movies) }
            waitUntil(timeoutMillis = UiTimeoutMs) { focusedTitle()?.endsWith("title 1") == true }
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            test()
        }
    }

    private fun DesktopComposeUiTest.focused(): SemanticsNode? =
        onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun DesktopComposeUiTest.focusedTitle(): String? {
        fun SemanticsNode.texts(): List<String> =
            (config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) + children.flatMap { it.texts() }
        return focused()?.texts()?.firstOrNull { it in titles || it in letters }
    }

    private fun DesktopComposeUiTest.press(key: Key, settleMs: Long = 800) {
        onRoot().performKeyInput { pressKey(key) }
        mainClock.advanceTimeBy(settleMs)
        waitForIdle()
    }

    private fun DesktopComposeUiTest.focusedTop(): Float? = focused()?.boundsInRoot?.let(Rect::top)

    @Test
    fun `moving along a grid row leaves the row where it is`() = onTvGrid {
        repeat(4) { press(Key.DirectionDown) }
        val rowTop = focusedTop()!!
        mainClock.autoAdvance = false
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        val seen = mutableListOf<Float?>()
        repeat(60) {
            mainClock.advanceTimeByFrame()
            seen += focusedTop()
        }
        mainClock.autoAdvance = true
        assertTrue(seen.all { it == null || abs(it - rowTop) < 1f }, "the row moved off $rowTop on a sideways press: $seen")
    }

    private fun DesktopComposeUiTest.walkIntoBar(): String {
        repeat(4) { press(Key.DirectionDown) }
        var cell = focusedTitle()!!
        repeat(12) {
            press(Key.DirectionRight)
            val now = focusedTitle()!!
            if (now in letters) return cell
            cell = now
        }
        error("never reached the alphabet bar; on $cell")
    }

    @Test
    fun `leaving the alphabet bar unpicked goes back to the cell it came from`() = onTvGrid {
        val cell = walkIntoBar()
        repeat(3) { press(Key.DirectionDown, settleMs = 200) }
        press(Key.DirectionLeft)
        assertEquals(cell, focusedTitle())
    }

    @Test
    fun `leaving the alphabet bar lands on the letter just picked`() = onTvGrid {
        walkIntoBar()
        var steps = 0
        while (focusedTitle() != "M") {
            check(steps++ < 40) { "never reached M; on ${focusedTitle()}" }
            press(if (focusedTitle()!! < "M") Key.DirectionDown else Key.DirectionUp, settleMs = 200)
        }
        press(Key.Enter, settleMs = 1_500)
        press(Key.DirectionLeft)
        assertEquals("Mtitle 1", focusedTitle())
    }
}
