package dev.kobbler.kobplayer

import kotlin.math.pow
import kotlin.math.roundToInt

private const val TARGET_LUFS = -14.0

// Half the web player's +6 dB: VLC applies volume after its audio filters, so no limiter
// can catch a boosted peak and anything past full scale clips.
private const val MAX_BOOST_DB = 3.0

fun loudnessGain(lufs: Double?): Float =
    lufs?.let { 10.0.pow(minOf(TARGET_LUFS - it, MAX_BOOST_DB) / 20).toFloat() } ?: 1f

internal fun vlcVolume(volume: Float, normalization: Float, maxVolume: Float): Int =
    ((volume * normalization).coerceIn(0f, maxVolume) * 100).roundToInt()
