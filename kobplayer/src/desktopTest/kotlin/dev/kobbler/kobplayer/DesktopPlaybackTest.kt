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

    private fun withPlayer(
        config: PlayerConfig = PlayerConfig(hardwareAcceleration = false),
        block: suspend (DesktopPlayer) -> Unit,
    ) {
        DesktopPlayer.prepareLibraries()
        val scope = Dispatchers.Default + SupervisorJob()
        val player = DesktopPlayer(scope, config)
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
    fun `a network title is read in bursts with the source left idle in between`() =
        withPlayer(PlayerConfig(hardwareAcceleration = false, readAhead = ReadAhead(bufferKb = 32))) { player ->
            player.load(MediaSource.Url(served("noise_176x100_20s.mkv")))
            player.currentMs.await("under way") { it > 1_000 }
            val bytesRead = List(120) {
                player.vlcPlayer.inputBytesRead().also { delay(50) }
            }
            val reading = bytesRead.zipWithNext { a, b -> b > a }
            val bursts = reading.zipWithNext { was, now -> !was && now }.count { it }
            val longestIdleMs = reading.fold(0 to 0) { (run, best), read ->
                if (read) 0 to best else (run + 1).let { it to maxOf(best, it) }
            }.second * 50
            assertTrue(bursts >= 2 && longestIdleMs >= 1_000, "$bursts bursts, longest idle ${longestIdleMs}ms: $bytesRead")
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

    /** Hands out the first [fraction] of the file and then goes away, as a server restarting does. */
    private fun dying(resource: String, fraction: Double): String {
        val bytes = File(javaClass.getResource("/$resource")!!.toURI()).readBytes()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/x-matroska")
            // Chunked, so the cut reads as a clean end of stream rather than a short body.
            exchange.sendResponseHeaders(200, 0)
            runCatching { exchange.responseBody.write(bytes, 0, (bytes.size * fraction).toInt()) }
            exchange.close()
            Thread { server.stop(0) }.start()
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/$resource"
    }

    /**
     * libVLC 4 reports a stream that dropped out the same way as a title that ran out. Short
     * of the end it is the former, and an error is what lets the app reconnect instead of
     * moving on to the next episode.
     */
    @Test
    fun `a stream that dies short of the end is an error, not the end`() = withPlayer { player ->
        player.load(MediaSource.Url(dying("red_176x100_subtitled.mkv", fraction = 0.4)))
        val settled = player.state.await("playback to stop") { it == PlayerState.ERROR || it == PlayerState.FINISHED }
        assertEquals(PlayerState.ERROR, settled, "stopped at ${player.currentMs.value} of ${player.durationMs.value}")
        assertTrue(player.error.value != null)
    }

    /** A channel has no end: a live stream that stops has dropped out, however far it got. */
    @Test
    fun `a live stream that stops is an error even at the end of its bytes`() = withPlayer { player ->
        player.load(MediaSource.Url(dying("red_176x100_sar4-3.mkv", fraction = 1.0), live = true))
        val settled = player.state.await("playback to stop") { it == PlayerState.ERROR || it == PlayerState.FINISHED }
        assertEquals(PlayerState.ERROR, settled, "stopped at ${player.currentMs.value} of ${player.durationMs.value}")
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
