package dev.kobbler.kobplayer

/**
 * What this device's hardware decoder has been observed to do with field pictures.
 *
 * DVB-T H.264 is PAFF — one coded picture per field — and some hardware AVC decoders
 * accept it and then emit nothing at all, forever, while the audio plays on. VLC does not
 * consider that an error: no event, no error state, nothing to recover from. It is only
 * visible as a clock that runs against a picture that never arrives.
 *
 * A list of chip names cannot keep up with that — a wrong guess is either a black screen
 * or a working decoder made slow — so the device answers for itself, once, and the answer
 * is kept.
 */
enum class FieldDecodeVerdict {
    Unknown,
    HardwareDecodes,
    HardwareEmitsNothing,
}

/**
 * How long a field-coded tune may run with sound and no picture before its decoder is
 * judged to be the silent kind.
 *
 * Sound ahead of picture is ordinary here: a tuner joining mid-GOP waits for the next IDR,
 * and a television's own tuner is visibly slower than that on a bad day. So this has to sit
 * well past any honest wait — a decoder that is going to emit has emitted long before. The
 * cost of being generous is ten seconds, once, on a device that turns out to be broken; the
 * cost of being tight is a healthy device stranded in software for good.
 */
const val FieldDecodeProbeMs = 10_000L

/**
 * Whether this tune has waited long enough to conclude the decoder will never emit.
 *
 * [playedMs] is the media clock's own progress, not wall time: a tune that is buffering or
 * paused has not been given its chance yet, and must not be judged on it.
 */
fun fieldDecodeProbeFailed(
    fieldCoded: Boolean,
    verdict: FieldDecodeVerdict,
    presentedVideo: Boolean,
    playedMs: Long,
): Boolean =
    fieldCoded &&
        verdict == FieldDecodeVerdict.Unknown &&
        !presentedVideo &&
        playedMs >= FieldDecodeProbeMs

/** Whether to take MediaCodec away before this source is opened at all. */
fun softwareDecodeRequired(fieldCoded: Boolean, verdict: FieldDecodeVerdict): Boolean =
    fieldCoded && verdict == FieldDecodeVerdict.HardwareEmitsNothing

/**
 * What the fallback proved.
 *
 * Only a software reload that produced a picture is evidence about the hardware. One that
 * is black as well was never the decoder's fault — an aerial that has stopped delivering
 * looks identical from here — and recording it as such would strand the device on the slow
 * path over a passing fault.
 */
fun verdictAfterFallback(softwarePresentedVideo: Boolean): FieldDecodeVerdict =
    if (softwarePresentedVideo) FieldDecodeVerdict.HardwareEmitsNothing else FieldDecodeVerdict.Unknown
