package dev.kobbler.app.ui.detail.tvshow

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AirDateLabelTest {
    private val today = LocalDate(2026, 9, 26)

    @Test
    fun labels() {
        listOf(
            LocalDate(2024, 3, 3) to "Mar 3, 2024",
            LocalDate(2026, 9, 1) to "Sep 1, 2026",
            today to "Today",
            LocalDate(2026, 10, 3) to "Airs Oct 3",
            LocalDate(2027, 1, 8) to "Airs Jan 8, 2027",
        ).forEach { (date, expected) -> assertEquals(expected, airDateLabel(date, today), "for $date") }
    }
}
