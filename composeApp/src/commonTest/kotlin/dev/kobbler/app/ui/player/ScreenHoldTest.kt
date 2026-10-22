package dev.kobbler.app.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ScreenHoldTest {

    @Test
    fun `a paused player lets the screen sleep once the grace runs out`() = runTest {
        val playing = MutableStateFlow(false)
        var held: Boolean? = null
        val job = launch { holdScreenWhilePlaying(playing, grace = 30.seconds) { held = it } }

        runCurrent()
        assertEquals(true, held, "a starting player must hold the screen while it opens")

        playing.value = true
        runCurrent()
        playing.value = false
        advanceTimeBy(29.seconds)
        runCurrent()
        assertEquals(true, held, "a brief pause released the screen")

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(false, held, "a paused player kept the screen on")

        playing.value = true
        runCurrent()
        assertEquals(true, held, "resuming did not hold the screen again")
        job.cancel()
    }
}
