package dev.kobbler.kobplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopGainFadeTest {

    private class Session(val player: DesktopPlayer, val scope: CoroutineScope) {
        val levels: MutableList<Int> = Collections.synchronizedList(mutableListOf())

        init {
            scope.launch { player.gainLevel.collect { levels += it } }
        }

        fun levelsDuring(block: () -> Unit): List<Int> {
            levels.clear()
            block()
            return synchronized(levels) { levels.toList() }
        }

        fun awaitState(state: PlayerState) = runBlocking {
            withTimeout(10_000) { player.state.first { it == state } }
        }

        fun awaitLevel(level: Int) = runBlocking {
            withTimeout(5_000) { player.gainLevel.first { it == level } }
        }
    }

    private fun session(block: Session.() -> Unit) {
        DesktopPlayer.prepareLibraries()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val player = DesktopPlayer(scope.coroutineContext, PlayerConfig(hardwareAcceleration = false))
        try {
            Session(player, scope).block()
        } finally {
            player.release()
            scope.cancel()
        }
    }

    private fun assertRamped(levels: List<Int>, from: Int, to: Int, what: String) {
        val path = listOf(from) + levels
        assertEquals(to, path.last(), "$what: never reached $to, saw $levels")
        assertTrue(path.any { it in minOf(from, to) + 1 until maxOf(from, to) }, "$what: jumped $from → $to with no ramp, saw $levels")
        assertTrue(path.zipWithNext().all { (a, b) -> if (to > from) b >= a else b <= a }, "$what: not monotonic, saw $levels")
    }

    private fun Session.start() {
        player.load(MediaSource.Url(resourceUrl("red_176x100_sar4-3.mkv")))
        awaitState(PlayerState.PLAYING)
        awaitLevel(100)
    }

    @Test
    fun startFadesIn() = session {
        val levels = levelsDuring { start() }
        assertRamped(levels, 0, 100, "start")
    }

    @Test
    fun pauseFadesOutBeforePausingAndResumeFadesIn() = session {
        start()
        val pausing = levelsDuring {
            player.pause()
            awaitState(PlayerState.PAUSED)
        }
        assertRamped(pausing, 100, 0, "pause")
        assertEquals(0, player.gainLevel.value, "paused with gain still up")

        val resuming = levelsDuring {
            player.play()
            awaitState(PlayerState.PLAYING)
            awaitLevel(100)
        }
        assertRamped(resuming, 0, 100, "resume")
    }

    @Test
    fun togglePlayPauseFadesBothWays() = session {
        start()
        val pausing = levelsDuring {
            player.togglePlayPause()
            awaitState(PlayerState.PAUSED)
        }
        assertRamped(pausing, 100, 0, "toggle to pause")

        val resuming = levelsDuring {
            player.togglePlayPause()
            awaitState(PlayerState.PLAYING)
            awaitLevel(100)
        }
        assertRamped(resuming, 0, 100, "toggle to play")
    }

    @Test
    fun playDuringFadeOutCancelsThePause() = session {
        start()
        player.pause()
        player.play()
        Thread.sleep(500)
        assertEquals(PlayerState.PLAYING, player.state.value)
        assertEquals(100, player.gainLevel.value)
    }
}
