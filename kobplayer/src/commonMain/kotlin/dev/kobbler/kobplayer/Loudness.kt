package dev.kobbler.kobplayer

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

private const val TARGET_LUFS = -20.0
private const val MAX_BOOST_DB = 6.0
private const val PEAK_CEILING_DB = -1.0

// VLC folds surround down to stereo without normalizing and applies volume after its audio
// filters, so no limiter can catch a boosted peak. Without the fold-down's measured peak,
// only this blanket ceiling stands between a boost and clipping.
private const val UNMEASURED_MAX_BOOST_DB = 3.0

/** [downmixPeakDb] is the true peak (dBTP) of the stereo fold-down the player renders. */
data class Loudness(val lufs: Double, val downmixPeakDb: Double? = null)

/** Cuts are never deepened by the peak: levelling should not invent attenuation of its own. */
fun loudnessGain(loudness: Loudness?): Float {
    loudness ?: return 1f
    val ceiling = loudness.downmixPeakDb
        ?.let { minOf(MAX_BOOST_DB, maxOf(PEAK_CEILING_DB - it, 0.0)) }
        ?: UNMEASURED_MAX_BOOST_DB
    return 10.0.pow(minOf(TARGET_LUFS - loudness.lufs, ceiling) / 20).toFloat()
}

/**
 * How a libVLC audio output turns its volume into amplitude. VLC's software volume and its
 * desktop and Apple outputs cube it; Android's AAudio and AudioTrack streams pass volumes up
 * to 1 to the platform, which is linear, and cube only the boost above that.
 */
internal enum class VolumeCurve {
    Cubic,
    Android;

    fun amplitude(volume: Float): Float = when {
        this == Android && volume <= 1f -> volume
        else -> volume * volume * volume
    }

    fun volume(amplitude: Float): Float = when {
        this == Android && amplitude <= 1f -> amplitude
        else -> cbrt(amplitude)
    }
}

/** [normalization] is a plain amplitude factor; it is applied on top of the user's [volume] as VLC hears it. */
internal fun vlcVolume(volume: Float, normalization: Float, maxVolume: Float, curve: VolumeCurve): Int =
    (curve.volume(curve.amplitude(volume) * normalization).coerceIn(0f, maxVolume) * 100).roundToInt()
