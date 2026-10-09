package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class SeekTargetTest {

    @Test
    fun keepsTargetWhileDurationIsStillUnknown() {
        // A resume seek is issued before the demuxer has reported a length. Clamping
        // against a zero duration silently rewrote every such seek to 0, so a slow parse
        // restarted the title from the beginning instead of resuming it.
        assertEquals(1_924_000, clampSeekTarget(1_924_000, durationMs = 0))
    }

    @Test
    fun clampsToDurationOnceKnown() {
        assertEquals(90_000, clampSeekTarget(120_000, durationMs = 90_000))
        assertEquals(45_000, clampSeekTarget(45_000, durationMs = 90_000))
    }

    @Test
    fun neverGoesNegative() {
        assertEquals(0, clampSeekTarget(-5_000, durationMs = 90_000))
        assertEquals(0, clampSeekTarget(-5_000, durationMs = 0))
    }

    @Test
    fun treatsNegativeDurationAsUnknown() {
        assertEquals(1_924_000, clampSeekTarget(1_924_000, durationMs = -1))
    }
}
