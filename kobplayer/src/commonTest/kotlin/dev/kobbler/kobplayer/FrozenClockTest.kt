package dev.kobbler.kobplayer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A transfer that goes quiet freezes the picture with the player still saying it plays:
 * libVLC reports buffering only while a title opens or a seek lands. The clock standing
 * still is the only sign, and it is what puts the spinner up and, past any hope of the
 * transfer resuming by itself, hands the title to the app to reopen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrozenClockTest {

    private class Harness {
        val state = MutableStateFlow(PlayerState.PLAYING)
        val position = MutableStateFlow(0L)
        var held = false
        val verdicts = mutableListOf<ClockVerdict>()
    }

    private fun watching(body: suspend kotlinx.coroutines.test.TestScope.(Harness) -> Unit) = runTest {
        val h = Harness()
        val job = launch {
            superviseClock(h.state, h.position, held = { h.held }, now = { testScheduler.currentTime }) { h.verdicts += it }
        }
        body(h)
        job.cancel()
    }

    @Test
    fun aClockThatStopsWhilePlayingIsAStallAndThenDead() = watching { h ->
        advanceTimeBy(FROZEN_STALL_MS + ClockPollMs)
        assertEquals(listOf(ClockVerdict.Stalled), h.verdicts)
        advanceTimeBy(FROZEN_DEAD_MS)
        assertEquals(listOf(ClockVerdict.Stalled, ClockVerdict.Dead), h.verdicts)
    }

    @Test
    fun aClockThatMovesAgainEndsTheStall() = watching { h ->
        advanceTimeBy(FROZEN_STALL_MS + ClockPollMs)
        h.position.value = 5_000L
        advanceTimeBy(ClockPollMs * 2)
        assertEquals(listOf(ClockVerdict.Stalled, ClockVerdict.Moving), h.verdicts)
    }

    @Test
    fun aMovingClockIsNeverAStall() = watching { h ->
        repeat(200) {
            h.position.value += 250
            advanceTimeBy(250)
        }
        assertEquals(emptyList(), h.verdicts)
    }

    @Test
    fun theWaitStartsOverWhenPlaybackDoes() = watching { h ->
        advanceTimeBy(FROZEN_STALL_MS - 1_000)
        h.state.value = PlayerState.PAUSED
        advanceTimeBy(60_000)
        h.state.value = PlayerState.PLAYING
        advanceTimeBy(FROZEN_STALL_MS - 1_000)
        assertEquals(emptyList(), h.verdicts)
    }

    @Test
    fun onlyPlaybackCounts() {
        for (state in PlayerState.entries - PlayerState.PLAYING) {
            watching { h ->
                h.state.value = state
                advanceTimeBy(FROZEN_DEAD_MS * 2)
                assertEquals(emptyList(), h.verdicts, "$state")
            }
        }
    }

    @Test
    fun aHeldPlayerIsNotStalled() = watching { h ->
        h.held = true
        advanceTimeBy(FROZEN_DEAD_MS * 2)
        assertEquals(emptyList(), h.verdicts)
    }

    private class Player {
        val state = MutableStateFlow(PlayerState.PLAYING)
        val position = MutableStateFlow(7_000L)
        val buffering = MutableStateFlow(false)
        val error = MutableStateFlow<Throwable?>(null)
    }

    private fun playing(body: suspend kotlinx.coroutines.test.TestScope.(Player) -> Unit) = runTest {
        val p = Player()
        val job = launch {
            watchFrozenClock(p.state, p.position, p.buffering, p.error, held = { false }, now = { testScheduler.currentTime })
        }
        body(p)
        job.cancel()
    }

    @Test
    fun aStallShowsTheSpinnerAndTakesItDownWhenTheClockMoves() = playing { p ->
        advanceTimeBy(FROZEN_STALL_MS + ClockPollMs)
        assertEquals(true, p.buffering.value)
        p.position.value = 7_500L
        advanceTimeBy(ClockPollMs * 2)
        assertEquals(false, p.buffering.value)
    }

    @Test
    fun aClockThatNeverComesBackIsAnErrorTheAppCanReopenFrom() = playing { p ->
        advanceTimeBy(FROZEN_DEAD_MS + ClockPollMs * 2)
        assertEquals(PlayerState.ERROR, p.state.value)
        assertEquals(true, p.error.value is PlaybackFroze, "error ${p.error.value}")
    }

    @Test
    fun libVlcsOwnBufferingIsLeftShowing() = playing { p ->
        advanceTimeBy(FROZEN_STALL_MS + ClockPollMs)
        p.state.value = PlayerState.BUFFERING
        advanceTimeBy(ClockPollMs * 2)
        assertEquals(true, p.buffering.value)
    }
}
