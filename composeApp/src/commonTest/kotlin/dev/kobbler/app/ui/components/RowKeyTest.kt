package dev.kobbler.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class RowKeyTest {

    @Test
    fun aResolvedRowIsUsed() {
        assertEquals(4, rowKeyFor(resolvedRow = 4, previous = 3))
    }

    @Test
    fun anUnresolvedRowHoldsThePreviousOne() {
        // Mid-scroll the focused item is not in layoutInfo yet. Inventing a key from the
        // item index made a move along one row look like a row change, which re-ran the
        // centering and threw the list vertically on a left/right press.
        assertEquals(4, rowKeyFor(resolvedRow = null, previous = 4))
    }

    @Test
    fun theFirstFocusWithNothingResolvedIsStillDistinctFromNoFocus() {
        assertEquals(null, rowKeyFor(resolvedRow = null, previous = null))
    }
}
