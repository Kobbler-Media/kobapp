package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class SurfaceReturnTest {

    @Test
    fun aPausedTitleDefersItsRedraw() {
        mapOf(
            PlayerState.PLAYING to SurfaceReturnRedraw.Now,
            PlayerState.BUFFERING to SurfaceReturnRedraw.Now,
            PlayerState.PAUSED to SurfaceReturnRedraw.Deferred,
            PlayerState.IDLE to SurfaceReturnRedraw.None,
            PlayerState.LOADING to SurfaceReturnRedraw.None,
            PlayerState.FINISHED to SurfaceReturnRedraw.None,
            PlayerState.ERROR to SurfaceReturnRedraw.None,
        ).forEach { (state, expected) ->
            assertEquals(expected, surfaceReturnRedraw(state), state.toString())
        }
    }
}
