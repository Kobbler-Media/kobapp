package dev.kobbler.kobplayer

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameRateProbeTest {

    @Test
    fun `counts the pictures over a clean stretch of playback`() = runTest {
        val state = MutableStateFlow(PlayerState.PLAYING)
        val rate = probeFrameRate(state, settleMs = 1_000, windowMs = 4_000) { currentTime * 24 / 1000 to currentTime }
        assertEquals(24f, rate)
    }

    @Test
    fun `does not wake while paused`() = runTest {
        val state = MutableStateFlow(PlayerState.PAUSED)
        var samples = 0
        val probe = async {
            probeFrameRate(state, settleMs = 1_000, windowMs = 4_000) { samples++; currentTime * 25 / 1000 to currentTime }
        }
        advanceTimeBy(60 * 60_000)
        runCurrent()
        assertEquals(0, samples, "sampled a paused player")

        state.value = PlayerState.PLAYING
        assertEquals(25f, probe.await())
    }

    @Test
    fun `gives up on a stream that never shows a clean stretch`() = runTest {
        val state = MutableStateFlow(PlayerState.PLAYING)
        var samples = 0
        val rate = probeFrameRate(state, settleMs = 1_000, windowMs = 4_000) { samples++; null }
        assertEquals(0f, rate)
        assertTrue(samples <= FrameRateProbeAttempts, "kept sampling: $samples")
    }
}
