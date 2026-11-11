package dev.kobbler.kobplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A libVLC call that never returns (a stop waiting on an input whose decoder deadlocked)
 * held every command behind it: the next title sat on "Starting" for good. What comes
 * after such a call runs on a fresh player instead.
 */
class DesktopWedgedPlayerTest {

    private val stuck = CountDownLatch(1)

    @AfterTest
    fun unstick() = stuck.countDown()

    private suspend fun DesktopPlayer.playing(resource: String) {
        load(MediaSource.Url(resourceUrl(resource)))
        currentMs.await("$resource under way") { it > 300 }
    }

    // Waits as vlcj's stop does, on a latch it gives up on when interrupted.
    private fun DesktopPlayer.wedge() {
        val started = CountDownLatch(1)
        vlc {
            started.countDown()
            try {
                stuck.await()
            } catch (_: InterruptedException) {
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS), "the stuck call never started")
    }

    private suspend fun DesktopPlayer.showsNewFrames() {
        val sink = video!!
        val seen = sink.frameVersion.intValue
        val shown = withTimeoutOrNull(10_000) { while (sink.frameVersion.intValue <= seen + 2) delay(20) }
        assertNotNull(shown, "no frames from the fresh player")
    }

    @Test
    fun `a title loaded behind a stuck call plays on a fresh player`() = withPlayer { player ->
        player.playing("red_176x100_sar4-3.mkv")
        player.wedge()
        player.load(MediaSource.Url(resourceUrl("red_176x100_subtitled.mkv")))
        player.durationMs.await("the new title's length") { it in 9_900..10_100 }
        player.currentMs.await("the new title under way") { it > 300 }
        assertEquals(PlayerState.PLAYING, player.state.value)
        player.showsNewFrames()
    }

    @Test
    fun `a player given up on is reported, freed, and reported freed`() = withPlayer { player ->
        val stalls = mutableListOf<PlayerStall>()
        val watching = CoroutineScope(Dispatchers.Default).launch { player.stalls.collect { stalls += it } }
        try {
            player.playing("red_176x100_sar4-3.mkv")
            player.wedge()
            player.load(MediaSource.Url(resourceUrl("red_176x100_subtitled.mkv")))
            val settled = withTimeoutOrNull(10_000) { while (stalls.none { it.released != null }) delay(20) }
            assertNotNull(settled, "stalls: $stalls")
            val (given, freed) = stalls
            assertTrue("wedge" in given.stuckIn, "stuck in ${given.stuckIn}")
            assertTrue(given.stuckForMs >= WedgedVlcCallMs, "stuck for ${given.stuckForMs}ms")
            assertEquals(true, freed.released)
        } finally {
            watching.cancel()
        }
    }

    @Test
    fun `a seek behind a stuck call picks the title up there on a fresh player`() = withPlayer { player ->
        player.playing("noise_176x100_20s.mkv")
        player.wedge()
        player.seekTo(15_000)
        val at = player.currentMs.await("playing on from the seek") { it > 15_300 }
        assertTrue(at < 19_000, "position $at")
        assertEquals(PlayerState.PLAYING, player.state.value)
        player.showsNewFrames()
    }

    @Test
    fun `a pause behind a stuck call holds the title on a fresh player`() = withPlayer { player ->
        player.playing("red_176x100_subtitled.mkv")
        player.wedge()
        player.pause()
        player.state.await("paused") { it == PlayerState.PAUSED }
        val held = player.currentMs.value
        delay(1_000)
        assertTrue(player.currentMs.value - held < 300, "played on from $held to ${player.currentMs.value}")
        player.togglePlayPause()
        player.state.await("playing again") { it == PlayerState.PLAYING }
        val from = player.currentMs.value
        player.currentMs.await("clock moving after play") { it > from + 300 }
    }

    @Test
    fun `a stop behind a stuck call leaves the next title free to play`() = withPlayer { player ->
        player.playing("red_176x100_sar4-3.mkv")
        player.wedge()
        player.stop()
        assertEquals(PlayerState.IDLE, player.state.value)
        player.playing("red_176x100_subtitled.mkv")
        player.showsNewFrames()
    }

    @Test
    fun `releasing a player stuck in a call does not wait on it`() = withPlayer { player ->
        player.playing("red_176x100_sar4-3.mkv")
        player.wedge()
        val started = System.nanoTime()
        player.release()
        assertTrue(System.nanoTime() - started < 1_000_000_000, "release took ${(System.nanoTime() - started) / 1_000_000}ms")
    }
}
