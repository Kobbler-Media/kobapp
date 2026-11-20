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
            Loudness(-20.0, -3.0) to 0.0,
            Loudness(-14.0, -3.0) to -6.0,
            Loudness(-8.0, -0.1) to -12.0,
            Loudness(-23.04, -1.6) to 0.6,
            Loudness(-23.04, -6.0) to 3.04,
            Loudness(-30.0, -20.0) to 6.0,
            Loudness(-16.0, 0.5) to -4.0,
            Loudness(-26.0, 0.5) to 0.0,
        )
        for ((loudness, expectedDb) in cases) {
            val actual = db(loudnessGain(loudness))
            assertTrue(abs(actual - expectedDb) < 0.01, "$loudness: expected $expectedDb dB, got $actual dB")
        }
    }

    @Test
    fun aTitleWhosePeakIsNotMeasuredYetKeepsTheOldCeiling() {
        for ((lufs, expectedDb) in listOf(-20.0 to 0.0, -14.0 to -6.0, -22.0 to 2.0, -30.0 to 3.0)) {
            val actual = db(loudnessGain(Loudness(lufs)))
            assertTrue(abs(actual - expectedDb) < 0.01, "$lufs LUFS: expected $expectedDb dB, got $actual dB")
        }
        assertEquals(1f, loudnessGain(null))
    }

    @Test
    fun theLevellingGainIsWhatVlcActuallyAmplifiesBy() {
        val cases = listOf(
            Triple(VolumeCurve.Cubic, 1f, 1.65f),
            Triple(VolumeCurve.Cubic, 1f, 0.5f),
            Triple(VolumeCurve.Cubic, 1.5f, 0.5f),
            Triple(VolumeCurve.Cubic, 0.6f, 1.65f),
            Triple(VolumeCurve.Android, 1f, 1.65f),
            Triple(VolumeCurve.Android, 1f, 0.5f),
            Triple(VolumeCurve.Android, 1.2f, 0.5f),
            Triple(VolumeCurve.Android, 0.5f, 1.65f),
            Triple(VolumeCurve.Android, 0.8f, 1.65f),
        )
        for ((curve, volume, gain) in cases) {
            val reaching = vlcVolume(volume, gain, 2f, curve) / 100f
            val expectedDb = db(curve.amplitude(volume)) + db(gain)
            val actualDb = db(curve.amplitude(reaching))
            assertTrue(abs(actualDb - expectedDb) < 0.25, "$curve at $volume × $gain: expected $expectedDb dB, VLC plays $actualDb dB")
        }
    }

    @Test
    fun theLevelledVolumeStaysWithinWhatThePlayerCanOutput() {
        for (curve in VolumeCurve.entries) {
            assertEquals(200, vlcVolume(2f, 1.413f, 2f, curve))
            assertEquals(0, vlcVolume(0f, 1.413f, 2f, curve))
            assertEquals(100, vlcVolume(1f, 1f, 2f, curve))
        }
    }

    @Test
    fun theCurvesMatchLibVlcsOutputs() {
        assertEquals(8f, VolumeCurve.Cubic.amplitude(2f))
        assertEquals(0.125f, VolumeCurve.Cubic.amplitude(0.5f))
        assertEquals(8f, VolumeCurve.Android.amplitude(2f))
        assertEquals(0.5f, VolumeCurve.Android.amplitude(0.5f))
    }
}
