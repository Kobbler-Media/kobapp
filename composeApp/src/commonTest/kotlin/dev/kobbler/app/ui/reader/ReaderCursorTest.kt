package dev.kobbler.app.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderCursorTest {

    // Page height 600. Block 1 is pushed to page 1, leaving 300..599 as whitespace.
    private val bounds = mapOf(
        0 to 0..299,
        1 to 600..899,
        2 to 900..1100,
        3 to 1200..1400,
    )

    @Test
    fun pageTopResolvesToTheBlockUnderIt() {
        assertEquals(0, blockAtPageTop(bounds, scroll = 0))
        assertEquals(1, blockAtPageTop(bounds, scroll = 600))
        assertEquals(3, blockAtPageTop(bounds, scroll = 1200))
    }

    @Test
    fun pageTopLandingInWhitespaceFallsForwardToTheNextBlock() {
        assertEquals(1, blockAtPageTop(bounds, scroll = 400))
        assertEquals(3, blockAtPageTop(bounds, scroll = 1150))
    }

    @Test
    fun pageTopReturnsNullWhenNothingIsBelow() {
        assertNull(blockAtPageTop(bounds, scroll = 1500))
        assertNull(blockAtPageTop(emptyMap(), scroll = 0))
    }

    @Test
    fun stepCursorWalksNeighbouringBlocks() {
        assertEquals(1, stepCursor(bounds, from = 0, forward = true))
        assertEquals(0, stepCursor(bounds, from = 1, forward = false))
        assertEquals(3, stepCursor(bounds, from = 2, forward = true))
    }

    @Test
    fun stepCursorReturnsNullAtTheEnds() {
        assertNull(stepCursor(bounds, from = 3, forward = true))
        assertNull(stepCursor(bounds, from = 0, forward = false))
        assertNull(stepCursor(emptyMap(), from = 0, forward = true))
    }

    @Test
    fun stepCursorSkipsIndicesThatNeverReportedBounds() {
        // Only measured blocks are in the map; a gap in the keys must not stall the cursor.
        val sparse = mapOf(0 to 0..99, 4 to 100..199, 9 to 200..299)
        assertEquals(4, stepCursor(sparse, from = 0, forward = true))
        assertEquals(9, stepCursor(sparse, from = 4, forward = true))
        assertEquals(4, stepCursor(sparse, from = 9, forward = false))
    }

    @Test
    fun scrollToShowReturnsNullWhenTheBlockTopIsAlreadyOnScreen() {
        assertNull(scrollToShow(bounds, block = 1, scroll = 600, viewportHeight = 600, maxScroll = 1800))
        assertNull(scrollToShow(bounds, block = 2, scroll = 600, viewportHeight = 600, maxScroll = 1800))
    }

    @Test
    fun scrollToShowSnapsToThePageGrid() {
        // Never a free scroll to the block's own top — that would offset every later page turn.
        assertEquals(600, scrollToShow(bounds, block = 1, scroll = 0, viewportHeight = 600, maxScroll = 1800))
        assertEquals(1200, scrollToShow(bounds, block = 3, scroll = 600, viewportHeight = 600, maxScroll = 1800))
    }

    @Test
    fun scrollToShowScrollsBackwardsToo() {
        assertEquals(0, scrollToShow(bounds, block = 0, scroll = 1200, viewportHeight = 600, maxScroll = 1800))
    }

    @Test
    fun scrollToShowClampsToMaxScroll() {
        assertEquals(700, scrollToShow(bounds, block = 3, scroll = 0, viewportHeight = 600, maxScroll = 700))
    }

    @Test
    fun scrollToShowReturnsNullForUnmeasuredBlockOrViewport() {
        assertNull(scrollToShow(bounds, block = 42, scroll = 0, viewportHeight = 600, maxScroll = 1800))
        assertNull(scrollToShow(bounds, block = 1, scroll = 0, viewportHeight = 0, maxScroll = 1800))
    }
}
