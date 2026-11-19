package dev.kobbler.kobplayer

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Something the viewer saw go wrong that the player raised no error for: playback carried
 * on, so without a report it is only ever a viewer's description afterwards.
 */
data class PlaybackGlitch(val kind: Kind, val positionMs: Long, val detail: String, val trail: List<String>) {
    enum class Kind { PictureFrozen, PlayIgnored, ClockRacing, PicturesDropped }

    fun summary(): String = when (kind) {
        Kind.PictureFrozen -> "the picture stood still while the clock ran on"
        Kind.PlayIgnored -> "play was asked for and the player stayed paused"
        Kind.ClockRacing -> "the position ran faster than real time"
        Kind.PicturesDropped -> "pictures were dropped for arriving late"
    } + " at ${positionMs / 1000}s ($detail)"
}

/** VLC's own counters, read once a second. [steady]: playing at 1x, neither buffering nor held. */
internal data class PictureSample(
    val wallMs: Long,
    val clockMs: Long,
    val displayed: Long,
    val lost: Long,
    val steady: Boolean,
    val showsPicture: Boolean,
)

internal const val FrozenPictureMs = 3_000L
private const val RaceWindowMs = 10_000L
private const val RacingRate = 1.2
// Two seconds of a 25fps title; one or two late pictures around a seek are routine.
private const val DroppedPerWindow = 50L
// A clock step this far past the wall's is a seek, not playback.
private const val SeekStepMs = 1_500L

internal class GlitchWatch {
    private var last: PictureSample? = null
    private var window: PictureSample? = null
    private var frozenMs = 0L
    private var frozenReported = false

    fun sample(s: PictureSample): PlaybackGlitch? {
        val prev = last
        last = s
        val clockStep = prev?.let { s.clockMs - it.clockMs } ?: 0
        val wallStep = prev?.let { s.wallMs - it.wallMs } ?: 0
        if (prev == null || !s.steady || clockStep < 0 || clockStep > wallStep + SeekStepMs) {
            window = s.takeIf { it.steady }
            frozenMs = 0
            frozenReported = false
            return null
        }
        return frozen(prev, s, clockStep) ?: windowed(s)
    }

    private fun frozen(prev: PictureSample, s: PictureSample, clockStep: Long): PlaybackGlitch? {
        if (!s.showsPicture || s.displayed != prev.displayed) {
            frozenMs = 0
            frozenReported = false
            return null
        }
        frozenMs += clockStep
        if (frozenMs < FrozenPictureMs || frozenReported) return null
        frozenReported = true
        return glitch(PlaybackGlitch.Kind.PictureFrozen, s, "no new picture for ${frozenMs}ms of clock, ${s.displayed} shown in all")
    }

    private fun windowed(s: PictureSample): PlaybackGlitch? {
        val from = window ?: s.also { window = it }
        val wall = s.wallMs - from.wallMs
        if (wall < RaceWindowMs) return null
        window = s
        val clock = s.clockMs - from.clockMs
        val dropped = s.lost - from.lost
        return when {
            clock > wall * RacingRate ->
                glitch(PlaybackGlitch.Kind.ClockRacing, s, "${clock}ms of clock in ${wall}ms")
            s.showsPicture && dropped >= DroppedPerWindow ->
                glitch(PlaybackGlitch.Kind.PicturesDropped, s, "$dropped dropped in ${wall}ms, ${s.lost} in all")
            else -> null
        }
    }

    private fun glitch(kind: PlaybackGlitch.Kind, s: PictureSample, detail: String) =
        PlaybackGlitch(kind, s.clockMs, detail, emptyList())
}

/** Whether a play asked for as transport request [asked] left the player paused, no later request having superseded it. */
internal fun playIgnored(asked: Int, latest: Int, state: PlayerState, preloading: Boolean): Boolean =
    asked == latest && state == PlayerState.PAUSED && !preloading

/** What the player was told and did lately, for a report about what went wrong after it. */
@OptIn(ExperimentalAtomicApi::class)
internal class PlaybackTrail(private val capacity: Int = 40, private val now: () -> Long) {
    private val notes = AtomicReference(emptyList<Pair<Long, String>>())

    fun note(what: String) {
        val entry = now() to what
        while (true) {
            val before = notes.load()
            if (notes.compareAndSet(before, (before + entry).takeLast(capacity))) return
        }
    }

    fun lines(): List<String> {
        val at = now()
        return notes.load().map { (t, what) -> "-${(at - t) / 1000}.${(at - t) % 1000 / 100}s $what" }
    }
}
