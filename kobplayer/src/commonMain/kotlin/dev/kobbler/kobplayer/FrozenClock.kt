package dev.kobbler.kobplayer

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

internal enum class ClockVerdict { Moving, Stalled, Dead }

internal const val ClockPollMs = 500L

// libVLC raises no buffering event for a transfer that goes quiet mid-title, so a clock
// standing still while playing is the spinner's only cue.
internal const val FROZEN_STALL_MS = 2_000L

// Past the ten seconds in which kobvlc's HTTP access notices a dead connection and resumes
// on a new one, and well short of the wait a viewer puts up with: a clock still frozen
// after this is reopened from scratch rather than waited on.
internal const val FROZEN_DEAD_MS = 15_000L

/**
 * Watches the clock of a player that says it is playing, and reports each change in
 * whether it is moving: [ClockVerdict.Stalled] once it has stood still long enough to show
 * a spinner, [ClockVerdict.Moving] when it starts again, [ClockVerdict.Dead] once.
 */
internal suspend fun superviseClock(
    state: StateFlow<PlayerState>,
    position: StateFlow<Long>,
    held: () -> Boolean,
    now: () -> Long,
    onVerdict: (ClockVerdict) -> Unit,
) {
    var last = position.value
    var movedAt = now()
    var reported = ClockVerdict.Moving
    while (true) {
        delay(ClockPollMs)
        val at = now()
        val playing = state.value == PlayerState.PLAYING && !held()
        val verdict = if (!playing || position.value != last) {
            last = position.value
            movedAt = at
            ClockVerdict.Moving
        } else {
            when {
                at - movedAt >= FROZEN_DEAD_MS -> ClockVerdict.Dead
                at - movedAt >= FROZEN_STALL_MS -> ClockVerdict.Stalled
                else -> reported
            }
        }
        if (verdict != reported) {
            reported = verdict
            onVerdict(verdict)
        }
    }
}

internal class PlaybackFroze(positionMs: Long) :
    Exception("Playback froze at ${positionMs / 1000}s and did not come back")

private val clockOrigin = TimeSource.Monotonic.markNow()

internal suspend fun watchFrozenClock(
    state: MutableStateFlow<PlayerState>,
    position: StateFlow<Long>,
    isBuffering: MutableStateFlow<Boolean>,
    error: MutableStateFlow<Throwable?>,
    held: () -> Boolean,
    now: () -> Long = { clockOrigin.elapsedNow().inWholeMilliseconds },
) = superviseClock(state, position, held, now) { verdict ->
    when (verdict) {
        ClockVerdict.Moving -> if (state.value != PlayerState.BUFFERING && state.value != PlayerState.LOADING) {
            isBuffering.value = false
        }
        ClockVerdict.Stalled -> isBuffering.value = true
        // The app reopens a title that errors at the position it reached.
        ClockVerdict.Dead -> {
            error.value = PlaybackFroze(position.value)
            state.value = PlayerState.ERROR
        }
    }
}
