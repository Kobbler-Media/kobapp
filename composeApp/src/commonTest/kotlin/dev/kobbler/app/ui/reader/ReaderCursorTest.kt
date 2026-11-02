package dev.kobbler.app.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderCursorTest {

    private val onPage = listOf(3, 4, 7)

    @Test
    fun stepsWithinThePageAndStopsAtItsEdges() {
        data class Case(val from: Int, val forward: Boolean, val expected: Int?)
        for (case in listOf(
            Case(3, true, 4),
            Case(4, true, 7),
            Case(7, true, null),
            Case(7, false, 4),
            Case(3, false, null),
        )) {
            assertEquals(case.expected, stepCursor(onPage, case.from, case.forward), "$case")
        }
    }

    @Test
    fun aCursorLeftOffThePageRejoinsAtItsFirstBlock() {
        assertEquals(3, stepCursor(onPage, from = 12, forward = true))
        assertNull(stepCursor(emptyList(), from = 0, forward = true))
    }
}
