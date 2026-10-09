package dev.kobbler.app.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sitting on a card with a long title is idle; a marquee that never stops repaints the window every frame on a TV stick. */
@OptIn(ExperimentalTestApi::class)
class TvMarqueeTest {

    @Test
    fun `a focused title scrolls, then settles`() = runComposeUiTest {
        mainClock.autoAdvance = false
        var draws = 0
        setContent {
            Text(
                "A title far too long to fit in the width of one poster card",
                maxLines = 1,
                modifier = Modifier.width(80.dp).tvMarquee(focused = true).drawBehind { draws++ },
            )
        }
        mainClock.advanceTimeBy(3_000)
        assertTrue(draws > 2, "the title never scrolled")
        mainClock.advanceTimeBy(120_000)
        val settled = draws
        mainClock.advanceTimeBy(10_000)
        assertEquals(settled, draws, "the marquee was still repainting long after it was read")
    }
}
