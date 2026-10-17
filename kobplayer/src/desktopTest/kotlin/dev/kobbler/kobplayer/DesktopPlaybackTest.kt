package dev.kobbler.kobplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPlaybackTest {

    private fun withPlayer(block: suspend (DesktopPlayer) -> Unit) {
        DesktopPlayer.prepareLibraries()
        val scope = Dispatchers.Default + SupervisorJob()
        val player = DesktopPlayer(scope, PlayerConfig(hardwareAcceleration = false))
        try {
            runBlocking { withTimeout(20_000) { block(player) } }
        } finally {
            player.release()
            scope.cancel()
        }
    }

    private suspend fun <T> StateFlow<T>.await(what: String, predicate: (T) -> Boolean): T =
        runCatching { withTimeout(10_000) { first(predicate) } }.getOrElse { error("$what; last $value") }

    @Test
    fun `reports times in milliseconds`() = withPlayer { player ->
        player.load(MediaSource.Url(resourceUrl("red_176x100_sar4-3.mkv")))
        player.durationMs.await("duration") { it > 0 }
        assertTrue(player.durationMs.value in 2_900..3_100, "duration ${player.durationMs.value}")
        player.currentMs.await("position past 500 ms") { it > 500 }
        assertTrue(player.currentMs.value < 3_100, "position ${player.currentMs.value}")
    }

    @Test
    fun `settles into playing with a full buffer`() = withPlayer { player ->
        player.load(MediaSource.Url(resourceUrl("red_176x100_sar4-3.mkv")))
        player.currentMs.await("well under way") { it > 600 }
        assertEquals(PlayerState.PLAYING, player.state.value)
        assertEquals(1f, player.bufferPercent.value)
        assertEquals(false, player.isBuffering.value)
    }

    /**
     * A seek while paused re-buffers and moves the clock, and neither means playback
     * resumed. Reporting it as playing turned the next play press into a pause.
     */
    @Test
    fun `a seek while paused stays paused and play resumes from there`() = withPlayer { player ->
        player.load(MediaSource.Url(resourceUrl("red_176x100_subtitled.mkv")))
        player.state.await("playing") { it == PlayerState.PLAYING }
        player.currentMs.await("under way") { it > 500 }
        player.pause()
        player.state.await("paused") { it == PlayerState.PAUSED }

        player.seekTo(6_000)
        delay(1_500)
        assertEquals(PlayerState.PAUSED, player.state.value, "after seeking while paused")
        val parkedAt = player.currentMs.value

        player.togglePlayPause()
        player.state.await("playing again") { it == PlayerState.PLAYING }
        val resumedTo = player.currentMs.await("clock moving on from the seek") { it > parkedAt + 500 }
        assertTrue(resumedTo in 6_000..9_500, "resumed to $resumedTo")
    }

    @Test
    fun `resumes on the title's own clock`() = withPlayer { player ->
        player.load(MediaSource.Url(resourceUrl("red_176x100_subtitled.mkv"), startTimeSec = 5))
        player.state.await("playing") { it == PlayerState.PLAYING }
        val at = player.currentMs.await("position playing on from the resume point") { it > 5_300 }
        assertTrue(at < 9_000, "position $at")
        assertTrue(player.durationMs.value in 9_900..10_100, "duration ${player.durationMs.value}")
    }

    // How the app starts every title, Up Next included: load at the resume point, pause at
    // once, play when ready.
    @Test
    fun `resumes a title paused while it opens`() = listOf(
        { resourceUrl("red_176x100_subtitled.mkv") },
        { served("red_176x100_subtitled.mkv") },
    ).forEach { url -> listOf(false, true).forEach { replacing ->
        withPlayer { player ->
            if (replacing) {
                player.load(MediaSource.Url(resourceUrl("red_176x100_sar4-3.mkv")))
                player.currentMs.await("first title under way") { it > 300 }
            }
            player.load(MediaSource.Url(url(), startTimeSec = 5))
            player.pause()
            delay(1_000)
            player.play()
            player.state.await("playing") { it == PlayerState.PLAYING }
            delay(1_500)
            val at = player.currentMs.value
            assertTrue(at in 5_000..9_000, "position $at on ${player.state.value}, replacing $replacing")
        }
    } }

    @Test
    fun `pauses a title that is playing`() = withPlayer { player ->
        player.load(MediaSource.Url(served("red_176x100_subtitled.mkv")))
        player.currentMs.await("well under way") { it > 600 }
        player.pause()
        player.state.await("paused") { it == PlayerState.PAUSED }
        val held = player.currentMs.value
        delay(1_000)
        assertTrue(player.currentMs.value - held < 300, "advanced from $held to ${player.currentMs.value}")
    }

    @Test
    fun `finishes when the title runs out`() = withPlayer { player ->
        player.load(MediaSource.Url(resourceUrl("red_176x100_sar4-3.mkv")))
        player.state.await("finished") { it == PlayerState.FINISHED }
    }

    @Test
    fun `a stop is not an end`() = withPlayer { player ->
        player.load(MediaSource.Url(resourceUrl("red_176x100_subtitled.mkv")))
        player.state.await("playing") { it == PlayerState.PLAYING }
        player.stop()
        delay(1_000)
        assertEquals(PlayerState.IDLE, player.state.value)
    }

    // An input libVLC 4 is told to pause before it can is stopped instead, and a stream over
    // HTTP only becomes pausable once it is open.
    @Test
    fun `a preloaded title waits paused and then plays`() = listOf(
        { resourceUrl("red_176x100_subtitled.mkv") },
        { served("red_176x100_subtitled.mkv") },
    ).forEach { url -> preloads(url()) }

    private fun served(resource: String): String {
        val file = File(javaClass.getResource("/$resource")!!.toURI())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            Thread.sleep(150)
            val bytes = file.readBytes()
            val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
            val from = range ?: 0
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Type", "video/x-matroska")
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
            exchange.sendResponseHeaders(if (range != null) 206 else 200, (bytes.size - from).toLong())
            exchange.responseBody.use { it.write(bytes, from, bytes.size - from) }
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/$resource"
    }

    @Test
    fun `a pause while the title opens holds it rather than stopping it`() = withPlayer { player ->
        player.load(MediaSource.Url(served("red_176x100_subtitled.mkv")))
        player.pause()
        delay(1_500)
        assertTrue(player.currentMs.value < 300, "played on to ${player.currentMs.value}")
        assertTrue(player.state.value in setOf(PlayerState.PAUSED, PlayerState.LOADING), "state ${player.state.value}")
        player.play()
        player.currentMs.await("position advancing after play") { it > 500 }
        assertEquals(PlayerState.PLAYING, player.state.value)
    }

    private val servers = mutableListOf<HttpServer>()

    @kotlin.test.AfterTest
    fun stopServers() = servers.forEach { it.stop(0) }

    private fun preloads(url: String) = withPlayer { player ->
        player.setPreloading(true)
        player.load(MediaSource.Url(url))
        player.state.await("paused while preloading") { it == PlayerState.PAUSED }
        val held = player.currentMs.value
        delay(1_000)
        assertEquals(PlayerState.PAUSED, player.state.value)
        assertTrue(player.currentMs.value - held < 300, "advanced from $held to ${player.currentMs.value}")

        player.setPreloading(false)
        player.play()
        player.currentMs.await("position advancing after play") { it > held + 500 }
        assertEquals(PlayerState.PLAYING, player.state.value)
    }
}
