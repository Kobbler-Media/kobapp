package dev.kobbler.app.ui.detail.tvshow

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import dev.kobbler.app.platform.FormFactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The show page driven by a remote, through the real scaffold and its rail. */
@OptIn(ExperimentalTestApi::class)
class ShowDetailRemoteTest {

    @Test
    fun `entry focus is the play action`() = showPage {
        assertTrue(focusedText()!!.startsWith("Resume"), "entry focus was ${focusedText()}")
    }

    @Test
    fun `entry focus is the play action with a keyboard too`() = showPage(formFactor = FormFactor.Desktop) {
        runCatching { waitUntil(timeoutMillis = 5_000) { focusedText()?.startsWith("Resume") == true } }
        assertTrue(focusedText()!!.startsWith("Resume"), "entry focus was ${focusedText()}")
    }

    @Test
    fun `left from the play action opens the rail`() = showPage {
        press(Key.DirectionLeft)
        // The rail is driven by the scaffold's own key handling: opening it parks
        // focus on the scaffold root, which carries no label of its own.
        assertEquals("", focusedText(), "left landed on a page control instead of the rail")
    }

    @Test
    fun `up from an episode returns to the season it lists`() {
        for (column in 0..2) showPage {
            press(Key.DirectionDown)
            assertEquals("Season 3", focusedText(), "one press down from the actions")
            press(Key.DirectionLeft)
            press(Key.DirectionLeft)
            assertEquals("Season 1", focusedText())
            press(Key.DirectionDown)
            repeat(column) { press(Key.DirectionRight) }
            press(Key.DirectionUp)
            assertEquals("Season 1", focusedText(), "up from column $column")
        }
    }

    @Test
    fun `ok on a season tab opens that season's options without moving off it`() = showPage {
        press(Key.DirectionDown)
        assertEquals("Season 3", focusedText())
        press(Key.Enter)
        assertTrue(onAllNodesWithText("Mark season 3 watched").fetchSemanticsNodes().isNotEmpty(), "no season menu")
        assertTrue(onAllNodesWithText("S3E1 title").fetchSemanticsNodes().isNotEmpty(), "the season changed")
    }
}
