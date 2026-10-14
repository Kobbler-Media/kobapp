package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class SurfaceReturnTest {

    @Test
    fun redrawsOnlyATitleThatHasAlreadyShownPicture() {
        mapOf(
            PlayerState.PLAYING to true,
            PlayerState.PAUSED to true,
            PlayerState.BUFFERING to true,
            PlayerState.IDLE to false,
            PlayerState.LOADING to false,
            PlayerState.FINISHED to false,
            PlayerState.ERROR to false,
        ).forEach { (state, expected) ->
            assertEquals(expected, redrawsOnSurfaceReturn(state), state.toString())
        }
    }
}
