package dev.kobbler.app.ui.detail

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.ui.detail.tvshow.detailPage
import dev.kobbler.app.ui.detail.tvshow.focusedText
import dev.kobbler.app.ui.detail.tvshow.moveUntil
import dev.kobbler.app.ui.navigation.Destination
import kotlin.test.Test
import kotlin.test.assertTrue

/** On TV, entry focus on the action block must not scroll the title away, unless the focused control would be hidden. */
@OptIn(ExperimentalTestApi::class)
class PageTopPinTest {

    private fun movie(description: String) = mapOf<String, (HttpExchange) -> String>(
        "/api/v1/admin/movies/m1" to { _ ->
            """{"id":"m1","title":"Pinned Film","year":2020,"is_in_library":true,"description":"$description"}"""
        },
    )

    private fun DesktopComposeUiTest.settle() {
        repeat(3) { mainClock.advanceTimeBy(500); waitForIdle() }
    }

    private fun DesktopComposeUiTest.titleTop(): Float =
        onNodeWithText("Pinned Film", substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    private fun DesktopComposeUiTest.focusedVisible(): Boolean {
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val focused = onAllNodes(isFocused()).fetchSemanticsNodes().last().boundsInRoot
        return focused.top >= root.top && focused.bottom <= root.bottom
    }

    @Test
    // Play lands past the bottom edge margin but still fits with the page at the top. The
    // desktop owner does not bring entry focus into view, so this guards the outcome, not the pin.
    fun `an action block that fits at the top keeps the title on screen`() =
        detailPage(Destination.MovieDetail("m1"), movie("Medium synopsis. ".repeat(52))) {
            settle()
            assertTrue(titleTop() >= 0f, "title scrolled off on entry: top=${titleTop()}")
            moveUntil(Key.DirectionRight, { it != null && it != focusedText() }, maxSteps = 1)
            settle()
            assertTrue(titleTop() >= 0f, "title scrolled off after moving: top=${titleTop()}")
            assertTrue(focusedVisible())
        }

    @Test
    fun `a control below a long synopsis is scrolled into view rather than pinned off screen`() =
        detailPage(Destination.MovieDetail("m1"), movie("Long synopsis. ".repeat(400))) {
            settle()
            assertTrue(focusedVisible(), "focused control is off screen")
        }

}
