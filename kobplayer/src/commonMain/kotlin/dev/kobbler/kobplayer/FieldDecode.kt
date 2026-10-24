package dev.kobbler.kobplayer

/**
 * What this device's hardware decoder has been observed to do with field pictures.
 *
 * DVB-T H.264 is interlaced-coded (MBAFF, `frame_mbs_only_flag` 0), and some hardware AVC
 * decoders accept it and then emit nothing at all, forever, while the audio plays on. The
 * Tensor VPU drops any such stream, even one with no field macroblock in it. VLC does not
 * consider that an error: no event, no error state, nothing to recover from. It is only
 * visible as a clock that runs against a picture that never arrives.
 *
 * A list of chip names cannot keep up with that — a wrong guess is either a black screen
 * or a working decoder made slow — so the device answers for itself, with a self-test when
 * the system build or the app changes. Nothing seen during playback is recorded: a stream
 * slow to its first keyframe looks the same as a decoder that will never emit.
 */
enum class FieldDecodeVerdict {
    Unknown,
    HardwareDecodes,
    HardwareEmitsNothing,
}

/**
 * Media-clock time spent with a video track and no new picture.
 *
 * Counted on the media clock, not wall time: a tune that is buffering or paused has not been
 * given its chance yet. A clock that jumps further than a poll could account for is a seek,
 * and counts for nothing.
 */
class SilentDecoderWatch {
    private var lastPictures = 0L

    var lastClockMs = 0L
        private set
    private var sampled = false

    private var silentMs = 0L

    val silent: Boolean get() = silentMs >= SilentDecoderRescueMs

    fun sample(clockMs: Long, displayedPictures: Long, hasVideoTrack: Boolean) {
        val step = (clockMs - lastClockMs).takeIf { sampled && it in 0..MaxClockStepMs } ?: 0
        lastClockMs = clockMs
        sampled = true
        when {
            displayedPictures > lastPictures -> {
                lastPictures = displayedPictures
                silentMs = 0
            }
            hasVideoTrack -> silentMs += step
        }
    }

    private companion object {
        const val MaxClockStepMs = 2_000L
    }
}

/**
 * How long a tune may run with sound and no new picture before it is reopened in software.
 *
 * Only has to outlast the wait for a keyframe: a live join waits for the next one, and some
 * IPTV providers and local files put one only every 6 s. Early costs one title's battery.
 */
const val SilentDecoderRescueMs = 6_000L

/**
 * Whether a tune is watched for a decoder that never emits.
 *
 * An untested decoder is watched on field-coded sources. One known to emit nothing is
 * watched on everything else: a field-coded source already opens in software, and anything
 * else reaching it interlaced is a source the server did not know was.
 */
fun watchesForSilentDecoder(fieldCoded: Boolean, verdict: FieldDecodeVerdict): Boolean = when (verdict) {
    FieldDecodeVerdict.Unknown -> fieldCoded
    FieldDecodeVerdict.HardwareEmitsNothing -> !fieldCoded
    FieldDecodeVerdict.HardwareDecodes -> false
}

/** Whether to take MediaCodec away before this source is opened at all. */
fun softwareDecodeRequired(fieldCoded: Boolean, verdict: FieldDecodeVerdict): Boolean =
    fieldCoded && verdict == FieldDecodeVerdict.HardwareEmitsNothing

data class DecodedClip(val frames: Int, val meanLuma: Int?) {
    // The clips are white: 235 in limited range. A decoder that emits a frame of garbage or
    // black has not decoded it.
    val isWhite: Boolean get() = frames > 0 && (meanLuma ?: 0) >= 200
}

class SelfTestRun(val progressive: DecodedClip, val interlaced: DecodedClip)

/** [run] is null when there is no hardware AVC decoder to test. */
fun selfTestVerdict(run: SelfTestRun?): FieldDecodeVerdict = when {
    run == null -> FieldDecodeVerdict.HardwareDecodes
    !run.progressive.isWhite -> FieldDecodeVerdict.Unknown
    run.interlaced.isWhite -> FieldDecodeVerdict.HardwareDecodes
    else -> FieldDecodeVerdict.HardwareEmitsNothing
}
