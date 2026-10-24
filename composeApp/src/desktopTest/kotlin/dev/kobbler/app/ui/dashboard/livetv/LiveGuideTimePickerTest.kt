package dev.kobbler.app.ui.dashboard.livetv

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The picker offers whole hours the guide can answer for, and nothing past its horizon. */
class LiveGuideTimePickerTest {

    private val utc = TimeZone.UTC
    private val now = Instant.parse("2026-10-24T20:45:00Z")

    @Test
    fun `days run from today to the guide's horizon`() {
        val days = guideDays(now, utc)
        assertEquals(listOf("Today", "Tomorrow", "Mon 26"), days.map { it.label })
    }

    @Test
    fun `hours start after now and stop at the horizon`() {
        val hours = guideDays(now, utc).flatMap { it.hours }
        assertEquals(Instant.parse("2026-10-24T21:00:00Z"), hours.first())
        assertEquals(Instant.parse("2026-10-26T20:00:00Z"), hours.last())
        assertTrue(hours.all { it > now && it <= now + GuideFutureHours.hours })
        assertEquals(GuideFutureHours, hours.size)
    }
}
