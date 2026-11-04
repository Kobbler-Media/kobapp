package dev.kobbler.app.ui.reader

import kotlin.concurrent.Volatile
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Where a receiver is in a cast read, between the reports it sends once a second.
 *
 * A word lasts a few hundred milliseconds, so the highlight runs on from the last
 * report at the rate the receiver plays rather than jumping a second at a time.
 */
internal class TtsCastClock {
    private class Report(val positionMs: Long, val playing: Boolean, val at: TimeMark)

    @Volatile
    private var last = Report(0, false, TimeSource.Monotonic.markNow())

    fun report(positionSec: Double, playing: Boolean) {
        last = Report((positionSec * 1000).toLong(), playing, TimeSource.Monotonic.markNow())
    }

    fun hold() {
        last = Report(nowMs(), false, TimeSource.Monotonic.markNow())
    }

    fun nowMs(): Long = last.let { if (it.playing) it.positionMs + it.at.elapsedNow().inWholeMilliseconds else it.positionMs }
}

/** A sentence's place on a cast render's stream, from a buffered engine. */
internal data class CastSentence(val index: Int, val text: String, val audioOffsetMs: Int)

internal fun sentenceAt(sentences: List<CastSentence>, posMs: Long): CastSentence? =
    sentences.lastOrNull { it.audioOffsetMs <= posMs }
