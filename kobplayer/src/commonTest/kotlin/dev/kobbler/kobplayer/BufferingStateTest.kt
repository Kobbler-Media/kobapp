package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class BufferingStateTest {
    @Test
    fun bufferingInterruptsPlaybackButNeverStartsIt() {
        val cases = listOf(
            Triple(PlayerState.PLAYING, false, PlayerState.BUFFERING),
            Triple(PlayerState.BUFFERING, true, PlayerState.PLAYING),
            Triple(PlayerState.PAUSED, false, PlayerState.PAUSED),
            Triple(PlayerState.PAUSED, true, PlayerState.PAUSED),
            Triple(PlayerState.LOADING, true, PlayerState.LOADING),
        )
        for ((current, full, expected) in cases) {
            assertEquals(expected, stateOnBuffering(current, full), "$current full=$full")
        }
    }
}
