package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class PolledClockTest {

    private var now = 0L
    private val clock = PolledClock { now }

    private fun readings(start: Long, vararg reported: Long): List<Long> {
        var published = start
        return reported.map { published = clock.publish(published, it); published }
    }

    @Test
    fun `follows the clock while nothing is pending`() {
        assertEquals(listOf(1_000L, 1_250L, 900L), readings(750, 1_000, 1_250, 900))
    }

    // Measured against libVLC 4: for ~400 ms after setTime the clock still reads where the
    // seek left from, so a forward jump snapped back and then forward again.
    @Test
    fun `a seek holds until the clock reaches it`() {
        clock.seeked(20_000)
        assertEquals(listOf(20_000L, 20_000L, 20_236L, 20_471L), readings(20_000, 13_110, 13_144, 20_236, 20_471))
    }

    @Test
    fun `a backward seek holds the same way`() {
        clock.seeked(5_000)
        assertEquals(listOf(5_000L, 5_236L), readings(5_000, 35_200, 5_236))
    }

    @Test
    fun `a seek the clock never reaches stops holding`() {
        clock.seeked(20_000)
        assertEquals(listOf(20_000L), readings(20_000, 13_144))
        now += 5_000
        assertEquals(listOf(13_400L), readings(20_000, 13_400))
    }
}
