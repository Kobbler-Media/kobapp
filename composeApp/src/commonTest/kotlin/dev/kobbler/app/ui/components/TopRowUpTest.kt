package dev.kobbler.app.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopRowUpTest {

    @Test
    fun upIsSwallowedOnTheTopmostRow() {
        // The alphabet bar runs the full height down the right edge, so its early letters
        // sit geometrically above the first row and win the focus search. Nothing above
        // the top row is worth reaching, so the press is simply eaten.
        assertTrue(swallowsUp(focusedRowKey = 2, topRowKey = 2))
        assertTrue(swallowsUp(focusedRowKey = "watchnext", topRowKey = "watchnext"))
    }

    @Test
    fun upStillWorksEverywhereElse() {
        assertFalse(swallowsUp(focusedRowKey = 5, topRowKey = 2))
        // The first poster row must still reach the carousel sitting above it.
        assertFalse(swallowsUp(focusedRowKey = 3, topRowKey = "watchnext"))
    }

    @Test
    fun anUnknownRowNeverSwallows() {
        assertFalse(swallowsUp(focusedRowKey = null, topRowKey = 2))
        assertFalse(swallowsUp(focusedRowKey = 2, topRowKey = null))
    }
}
