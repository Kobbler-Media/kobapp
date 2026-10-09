package dev.kobbler.app.ui.dashboard.calendar

import dev.kobbler.app.platform.FormFactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgendaAnchorTest {

    private val groups = listOf(
        "2026-08-10" to 2,
        "2026-08-12" to 1,
        "2026-08-19" to 3,
        "2026-08-24" to 1,
    )

    @Test
    fun emptyRangeHasNoAnchor() {
        assertNull(agendaAnchor(emptyList(), "2026-08-19"))
    }

    @Test
    fun landsOnTodayCountingHeadersAndSpacers() {
        val anchor = agendaAnchor(groups, "2026-08-19")!!
        assertEquals("2026-08-19", anchor.date)
        assertTrue(anchor.isToday)
        // (header + 2 rows + spacer) + (header + 1 row + spacer) = 4 + 3
        assertEquals(7, anchor.headerIndex)
    }

    @Test
    fun landsOnNearestFollowingDayWhenTodayIsEmpty() {
        val anchor = agendaAnchor(groups, "2026-08-20")!!
        assertEquals("2026-08-24", anchor.date)
        assertEquals(false, anchor.isToday)
        assertEquals(12, anchor.headerIndex)
    }

    @Test
    fun firstGroupAnchorsAtZero() {
        val anchor = agendaAnchor(groups, "2026-07-01")!!
        assertEquals("2026-08-10", anchor.date)
        assertEquals(0, anchor.headerIndex)
    }

    @Test
    fun fallsBackToLastDayWhenWholeRangeIsPast() {
        val anchor = agendaAnchor(groups, "2026-09-01")!!
        assertEquals("2026-08-24", anchor.date)
        assertEquals(false, anchor.isToday)
        assertEquals(12, anchor.headerIndex)
    }

    @Test
    fun tvDropsBooksFromDefaultFilters() {
        assertEquals(setOf("tv", "movie", "sport"), defaultMediaFilters(FormFactor.TV))
        assertEquals(setOf("tv", "movie", "sport", "book"), defaultMediaFilters(FormFactor.Desktop))
        assertEquals(setOf("tv", "movie", "sport", "book"), defaultMediaFilters(FormFactor.Phone))
    }

    @Test
    fun booksChipIsLast() {
        assertEquals("book", MEDIA_KINDS.last().key)
    }
}
