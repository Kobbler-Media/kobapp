package dev.kobbler.app.ui.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.time.TimeSource

/**
 * Where the time between the tap and the picture goes.
 *
 * "Playback is slow" is not something the source can answer: the stages are a server
 * round trip, a demuxer, a hardware decoder and a surface, and which of them is holding
 * the other four up changes with the file, the network and whether it is the first play
 * of the run. So measure it. Every stage is one timestamp, so this costs nothing to leave
 * on, and [last] carries the reading somewhere it can be read off a TV without adb.
 *
 * Marks arrive from the coroutine driving the load, plus the collector watching the
 * player reach PLAYING; contention is a stray line in a diagnostic, not a fault worth
 * locking for.
 */
object PlaybackTrace {

    /** The most recent completed trace, oldest stage first. Rendered in Settings. */
    var last by mutableStateOf<String?>(null)
        private set

    private var origin: TimeSource.Monotonic.ValueTimeMark? = null
    private var previous: TimeSource.Monotonic.ValueTimeMark? = null
    private var subject = ""
    private val stages = mutableListOf<Pair<String, Long>>()

    /** Starts a new trace, discarding any half-finished one. */
    fun begin(what: String) {
        val now = TimeSource.Monotonic.markNow()
        origin = now
        previous = now
        subject = what
        stages.clear()
    }

    /** Records the time since the previous mark. No-op outside a trace. */
    fun mark(stage: String) {
        val before = previous ?: return
        val now = TimeSource.Monotonic.markNow()
        stages += stage to (now - before).inWholeMilliseconds
        previous = now
    }

    /** The stages recorded so far, for a start that is taking too long to wait for. */
    fun inProgress(): String? {
        origin ?: return null
        return "$subject — " + stages.joinToString(" | ") { "${it.first} ${it.second}" }
    }

    /**
     * Closes the trace at the first frame the viewer could see and publishes it.
     *
     * Ignored when no trace is open, because the player reaches PLAYING again on every
     * resume from a pause and only the one that follows a load is a start-up cost.
     */
    fun end() {
        val start = origin ?: return
        mark("frame")
        val total = start.elapsedNow().inWholeMilliseconds
        val line = "$subject — ${total}ms total | " + stages.joinToString(" | ") { "${it.first} ${it.second}" }
        last = line
        origin = null
        previous = null
        println("[PlaybackTrace] $line")
    }
}
