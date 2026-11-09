package dev.kobbler.kobplayer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoudnessGainTest {

    @Test
    fun aTitleIsLevelledTowardsTheTargetWithItsBoostCapped() {
        val cases = listOf(
            -14.0 to 1.0,
            -8.0 to 0.501,
            -17.0 to 1.413,
            -30.0 to 1.413,
        )
        for ((lufs, gain) in cases) {
            val actual = loudnessGain(lufs)
            assertTrue(abs(actual - gain) < 0.002, "$lufs LUFS: expected $gain, got $actual")
        }
        assertEquals(1f, loudnessGain(null))
    }

    @Test
    fun theLevelledVolumeStaysWithinWhatThePlayerCanOutput() {
        assertEquals(50, vlcVolume(1f, 0.5f, 2f))
        assertEquals(200, vlcVolume(2f, 1.413f, 2f))
        assertEquals(0, vlcVolume(0f, 1.413f, 2f))
    }
}
