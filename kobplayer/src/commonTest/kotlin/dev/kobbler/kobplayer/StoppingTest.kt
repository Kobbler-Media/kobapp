package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class StoppingTest {

    private data class Case(
        val current: PlayerState,
        val requested: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val expected: PlayerState,
        val live: Boolean = false,
    )

    @Test
    fun onlyAStopNobodyAskedForAtTheEndIsTheEndOfTheTitle() {
        listOf(
            Case(PlayerState.PLAYING, false, 59_000, 60_000, PlayerState.FINISHED),
            Case(PlayerState.BUFFERING, false, 59_500, 60_000, PlayerState.FINISHED),
            Case(PlayerState.PAUSED, false, 60_000, 60_000, PlayerState.FINISHED),
            Case(PlayerState.PLAYING, false, 20_000, 60_000, PlayerState.ERROR),
            Case(PlayerState.BUFFERING, false, 20_000, 60_000, PlayerState.ERROR),
            Case(PlayerState.PLAYING, false, 20_000, 0, PlayerState.FINISHED),
            Case(PlayerState.PLAYING, false, 2_700_000, 2_705_000, PlayerState.FINISHED),
            Case(PlayerState.PLAYING, false, 2_680_000, 2_705_000, PlayerState.ERROR),
            Case(PlayerState.PLAYING, false, 200, 10_000, PlayerState.ERROR),
            Case(PlayerState.PLAYING, false, 9_500, 10_000, PlayerState.FINISHED),
            Case(PlayerState.PLAYING, false, 2_600, 3_000, PlayerState.FINISHED),
            Case(PlayerState.PLAYING, true, 20_000, 60_000, PlayerState.PLAYING),
            Case(PlayerState.LOADING, true, 0, 60_000, PlayerState.LOADING),
            Case(PlayerState.IDLE, false, 0, 60_000, PlayerState.IDLE),
            Case(PlayerState.ERROR, false, 20_000, 60_000, PlayerState.ERROR),
            Case(PlayerState.PLAYING, false, 20_000, 0, PlayerState.ERROR, live = true),
            Case(PlayerState.PLAYING, false, 60_000, 60_000, PlayerState.ERROR, live = true),
            Case(PlayerState.PLAYING, true, 20_000, 0, PlayerState.PLAYING, live = true),
        ).forEach { case ->
            assertEquals(
                case.expected,
                stateOnStopping(case.current, case.requested, case.positionMs, case.durationMs, case.live),
                "$case",
            )
        }
    }
}
