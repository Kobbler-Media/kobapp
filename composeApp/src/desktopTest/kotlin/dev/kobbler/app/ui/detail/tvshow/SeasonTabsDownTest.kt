package dev.kobbler.app.ui.detail.tvshow

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SeasonTabsDownTest {

    @Test
    fun `down from any season tab lands on that season's first episode`() {
        for (season in listOf(1, 4, 7, 10, 12)) showPage(json = showJson(seasons = 12)) {
            press(Key.DirectionDown)
            assertEquals("Season 3", focusedText())
            val step = if (season < 3) Key.DirectionLeft else Key.DirectionRight
            moveUntil(step, { it == "Season $season" }, maxSteps = 12)
            assertEquals("Season $season", focusedText())
            press(Key.DirectionDown)
            val landed = focusedText()
            assertTrue(landed?.contains("S${season}E1 title") == true, "down from Season $season landed on $landed")
        }
    }
}
