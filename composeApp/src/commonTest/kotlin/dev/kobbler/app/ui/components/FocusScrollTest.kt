package dev.kobbler.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusScrollTest {

    // A 1080px viewport with the Home TV layout: hero 0..600, Up Next 600..916,
    // first rail 916..1436.
    private fun scroll(
        itemOffset: Int?,
        itemSize: Int?,
        restingBottom: Int? = null,
        viewportEnd: Int = 1080,
        viewportStart: Int = 0,
    ) = focusScroll(
        viewportStart = viewportStart,
        viewportEnd = viewportEnd,
        margin = 56,
        itemOffset = itemOffset,
        itemSize = itemSize,
        restingBottom = restingBottom,
    )

    @Test
    fun rowInsideBothMarginsDoesNotMove() {
        assertEquals(FocusScroll.Stay, scroll(itemOffset = 600, itemSize = 316, restingBottom = 916))
    }

    @Test
    fun rowClippedAtTheBottomEdgeStillMoves() {
        assertEquals(FocusScroll.Center(-280), scroll(itemOffset = 916, itemSize = 520, restingBottom = 1436))
    }

    @Test
    fun rowClippedAtTheTopEdgeStillMoves() {
        assertEquals(FocusScroll.Center(-380), scroll(itemOffset = -40, itemSize = 320))
    }

    @Test
    fun offScreenRowWhoseWholePrefixFitsGoesToTheTop() {
        assertEquals(FocusScroll.Top, scroll(itemOffset = null, itemSize = null, restingBottom = 916))
    }

    @Test
    fun partlyVisibleRowWhoseWholePrefixFitsGoesToTheTop() {
        assertEquals(FocusScroll.Top, scroll(itemOffset = -100, itemSize = 316, restingBottom = 916))
    }

    @Test
    fun rowWhosePrefixDoesNotFitCenters() {
        assertEquals(FocusScroll.Center(-280), scroll(itemOffset = null, itemSize = 520, restingBottom = 1436))
    }

    @Test
    fun unknownRestingPositionCenters() {
        assertEquals(FocusScroll.Center(-280), scroll(itemOffset = null, itemSize = 520))
    }

    @Test
    fun rowTallerThanTheViewportPinsToItsOwnTop() {
        assertEquals(FocusScroll.Center(0), scroll(itemOffset = 200, itemSize = 1400))
    }

    @Test
    fun unmeasuredRowCentersOnTheViewport() {
        assertEquals(FocusScroll.Center(-540), scroll(itemOffset = null, itemSize = null))
    }

    // A grid's top content padding puts the viewport start above the first item.
    @Test
    fun contentPaddingIsPartOfThePrefixBudget() {
        assertEquals(
            FocusScroll.Top,
            scroll(itemOffset = null, itemSize = null, restingBottom = 990, viewportStart = -32, viewportEnd = 1048),
        )
        assertEquals(
            FocusScroll.Center(-264),
            scroll(itemOffset = null, itemSize = 552, restingBottom = 1000, viewportStart = -32, viewportEnd = 1048),
        )
    }
}

class FocusEdgeScrollTest {

    private fun distance(
        offset: Float,
        size: Float,
        containerSize: Float = 1080f,
        hold: Boolean = false,
    ) = focusEdgeScrollDistance(
        offset = offset,
        size = size,
        containerSize = containerSize,
        margin = 56f,
        hold = hold,
    )

    @Test
    fun itemClearOfBothEdgesDoesNotMove() {
        assertEquals(0f, distance(offset = 685f, size = 195f))
    }

    @Test
    fun itemFlushAgainstTheBottomIsNudgedOffIt() {
        assertEquals(71f, distance(offset = 900f, size = 195f))
    }

    @Test
    fun itemFlushAgainstTheTopIsNudgedOffIt() {
        assertEquals(-36f, distance(offset = 20f, size = 195f))
    }

    @Test
    fun slackNeverExceedsHalfTheFreeSpace() {
        assertEquals(-20f, distance(offset = 0f, size = 1040f))
    }

    // Nothing above the top row is worth losing, so a request from it is answered with
    // zero rather than scrolled and undone.
    @Test
    fun heldScrollerRefusesAnOffScreenRequest() {
        assertEquals(0f, distance(offset = 900f, size = 195f, hold = true))
        assertEquals(0f, distance(offset = -400f, size = 195f, hold = true))
    }
}
