package dev.kobbler.app.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scroller re-asks the spec every frame until the distance it returns has been
 * scrolled; asking for scroll the page does not have keeps a frame callback armed for as
 * long as the page is open.
 */
@OptIn(ExperimentalFoundationApi::class)
class PageTopHoldTest {

    private fun spec(scrolled: Int) =
        PageTopHold(ScrollState(scrolled), enabled = false, edgeMarginPx = 28f).spec

    // The page registers itself as a focus target, and it is taller than the viewport.
    @Test
    fun thePageItselfAtRestAsksForNothing() {
        assertEquals(0f, spec(scrolled = 0).calculateScrollDistance(offset = 0f, size = 6000f, containerSize = 2400f))
    }

    @Test
    fun thePageItselfOnlyScrollsBackAsFarAsTheTop() {
        assertEquals(-100f, spec(scrolled = 100).calculateScrollDistance(offset = -100f, size = 6000f, containerSize = 2400f))
    }

    @Test
    fun aControlBelowTheFoldIsStillCentred() {
        assertEquals(2340f, spec(scrolled = 0).calculateScrollDistance(offset = 3480f, size = 120f, containerSize = 2400f))
    }
}
