package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackStallTest {

    @Test
    fun aSeekThatBuffersEndsWhenTheClockMovesAgain() {
        // VLC raises a buffering event on a seek but does not reliably close the cycle
        // with a 100% one, and only a full-cache event moved the player out of
        // BUFFERING. So a seek left the audiobook transport spinning for the rest of
        // the title while the narration played on underneath.
        assertEquals(PlayerState.PLAYING, stateAfterClockAdvance(PlayerState.BUFFERING))
    }

    @Test
    fun anOpenThatNeverReportedPlayingEndsTheSameWay() {
        assertEquals(PlayerState.PLAYING, stateAfterClockAdvance(PlayerState.LOADING))
    }

    @Test
    fun aPreloadKeepsItsState() {
        // A muted preload auto-starts and is paused again from the playing event. The
        // clock it ticks in between is not the viewer's playback.
        assertEquals(
            PlayerState.LOADING,
            stateAfterClockAdvance(PlayerState.LOADING, preloading = true),
        )
    }

    @Test
    fun aTapDuringAStallStillMeansStop() {
        // The transport used to act only on PLAYING and PAUSED, so for the length of
        // every buffering spell the button did nothing at all. BUFFERING is only ever
        // entered from playback and LOADING is a start already under way — either way
        // sound is coming or already here, and the tap means stop.
        assertEquals(PlayPauseIntent.Pause, playPauseIntent(PlayerState.BUFFERING))
        assertEquals(PlayPauseIntent.Pause, playPauseIntent(PlayerState.LOADING))
    }

    @Test
    fun aTapOnASettledPlayerToggles() {
        assertEquals(PlayPauseIntent.Pause, playPauseIntent(PlayerState.PLAYING))
        assertEquals(PlayPauseIntent.Play, playPauseIntent(PlayerState.PAUSED))
    }

    @Test
    fun aTapOnAPlayerWithNothingToPlayDoesNothing() {
        for (state in listOf(PlayerState.IDLE, PlayerState.FINISHED, PlayerState.ERROR)) {
            assertEquals(PlayPauseIntent.None, playPauseIntent(state))
        }
    }

    @Test
    fun statesThatAreNotStallsAreLeftAlone() {
        for (state in listOf(
            PlayerState.IDLE,
            PlayerState.PAUSED,
            PlayerState.PLAYING,
            PlayerState.FINISHED,
            PlayerState.ERROR,
        )) {
            assertEquals(state, stateAfterClockAdvance(state))
        }
    }
}
