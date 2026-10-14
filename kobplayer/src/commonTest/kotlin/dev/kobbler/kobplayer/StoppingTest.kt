package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class StoppingTest {

    @Test
    fun onlyAStopNobodyAskedForIsTheEndOfTheTitle() {
        listOf(
            Triple(PlayerState.PLAYING, false, PlayerState.FINISHED),
            Triple(PlayerState.BUFFERING, false, PlayerState.FINISHED),
            Triple(PlayerState.PAUSED, false, PlayerState.FINISHED),
            Triple(PlayerState.PLAYING, true, PlayerState.PLAYING),
            Triple(PlayerState.LOADING, true, PlayerState.LOADING),
            Triple(PlayerState.IDLE, false, PlayerState.IDLE),
            Triple(PlayerState.ERROR, false, PlayerState.ERROR),
        ).forEach { (current, requested, expected) ->
            assertEquals(expected, stateOnStopping(current, requested), "$current requested=$requested")
        }
    }
}
