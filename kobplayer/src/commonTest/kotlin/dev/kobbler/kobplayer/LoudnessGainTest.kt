package dev.kobbler.kobplayer

import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoudnessGainTest {

    private fun db(gain: Float) = 20 * log10(gain.toDouble())

    @Test
    fun aTitleIsLevelledTowardsTheTargetWithItsBoostCappedByItsDownmixPeak() {
        val cases = listOf(
            Loudness(-14.0, -3.0) to 0.0,
            Loudness(-8.0, -0.1) to -6.0,
            Loudness(-23.04, -1.6) to 0.6,
            Loudness(-23.04, -6.0) to 5.0,
            Loudness(-30.0, -20.0) to 6.0,
            Loudness(-16.0, 0.5) to 0.0,
        )
        for ((loudness, expectedDb) in cases) {
            val actual = db(loudnessGain(loudness))
            assertTrue(abs(actual - expectedDb) < 0.01, "$loudness: expected $expectedDb dB, got $actual dB")
        }
    }

    @Test
    fun aTitleWhosePeakIsNotMeasuredYetKeepsTheOldCeiling() {
        for ((lufs, expectedDb) in listOf(-14.0 to 0.0, -8.0 to -6.0, -17.0 to 3.0, -30.0 to 3.0)) {
            val actual = db(loudnessGain(Loudness(lufs)))
            assertTrue(abs(actual - expectedDb) < 0.01, "$lufs LUFS: expected $expectedDb dB, got $actual dB")
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
