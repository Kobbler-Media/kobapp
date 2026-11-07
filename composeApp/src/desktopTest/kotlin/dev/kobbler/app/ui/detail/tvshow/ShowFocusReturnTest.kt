package dev.kobbler.app.ui.detail.tvshow

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import dev.kobbler.app.UiTimeoutMs
import kotlin.test.Test
import dev.kobbler.app.ui.player.GlobalPlayerState
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whatever the remote just pressed on the show page — a stream picker, a want, a dialog —
 * focus has to come back somewhere the D-pad can move from once it is done.
 */
@OptIn(ExperimentalTestApi::class)
class ShowFocusReturnTest {

    private val streamable = showJson(inLibrary = { s, _ -> s < 3 })

    private val routes = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> String>(
        "/api/v1/admin/stream/search" to { _ ->
            """{"options":[{"id":"o1","release_title":"Remote.Show.S03E01.1080p","debrid_provider":"rd","debrid_provider_id":"rd1"}]}"""
        },
        "/api/v1/admin/tv/episodes/e3-1/want" to { _ -> "{}" },
        "/api/v1/admin/tv/episodes/e3-2/want" to { _ -> "{}" },
    )

    private fun streamablePage(json: String = streamable, test: DesktopComposeUiTest.() -> Unit) =
        showPage(json = json, routes = routes, canStream = true) { test() }

    private fun DesktopComposeUiTest.toFirstEpisode(action: String) {
        press(Key.DirectionDown)
        assertEquals("Season 3", focusedText())
        moveUntil(Key.DirectionDown, { it != "Season 3" })
        moveUntil(Key.DirectionRight, { it == action })
        assertEquals(action, focusedText())
    }

    private fun DesktopComposeUiTest.rightUntil(prefix: String) {
        moveUntil(Key.DirectionRight, { it?.startsWith(prefix) == true })
        assertEquals(true, focusedText()?.startsWith(prefix), "never reached $prefix, at ${focusedText()}")
    }

    private fun DesktopComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `closing an episode's stream options returns to the button that opened them`() = streamablePage {
        toFirstEpisode("Stream options")
        press(Key.Enter)
        waitUntil(timeoutMillis = UiTimeoutMs) { focusedText()?.contains("Remote.Show.S03E01.1080p") == true }
        press(Key.Back)
        assertFocusSettlesOn("Stream options")
    }

    @Test
    fun `wanting an episode from its row moves focus to the next action`() = streamablePage {
        toFirstEpisode("Want episode")
        press(Key.Enter)
        assertFocusSettlesOn("Stream options")
    }

    @Test
    fun `wanting the next episode from the action row moves focus to the next action`() =
        streamablePage(showJson(inLibrary = { s, _ -> s < 3 }, position = 0)) {
        rightUntil("Want")
        press(Key.Enter)
        assertFocusSettlesOn("More")
    }

    @Test
    fun `cancelling the want dialog returns to the menu that opened it`() = streamablePage {
        rightUntil("More")
        press(Key.Enter)
        awaitText("Want")
        moveUntil(Key.DirectionDown, { it == "Want" })
        assertEquals("Want", focusedText())
        press(Key.Enter)
        awaitText("Want this show")
        press(Key.Back)
        assertFocusSettlesOn("More")
    }

    @Test
    fun `the player covering the page from an episode's play control hands focus back when it closes`() {
        val gps = GlobalPlayerState()
        val androidFocus = AndroidLikeFocus()
        showPage(gps = gps, androidFocus = androidFocus) {
            press(Key.DirectionDown)
            moveUntil(Key.DirectionDown, { it != "Season 3" })
            val play = focusedText()
            assertTrue(play?.contains("S3E1 title") == true, "not on the episode's play control: $play")
            androidFocus.regrabs = 0

            runOnIdle { gps.isFullscreen = true }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertTrue(androidFocus.regrabs < AndroidLikeFocus.MAX_REGRABS, "eviction recursed ${androidFocus.regrabs} times")
            assertEquals(null, focusedText(), "focus stayed on the covered page")

            runOnIdle { gps.isFullscreen = false }
            assertFocusSettlesOn(play!!)
        }
    }
}
