package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.kobplayer.PlaybackGlitch
import dev.kobbler.kobplayer.PlaybackGlitch.Kind
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A frozen picture, a play that did not take or a clock running away raise no error, so
 * the only record of one is the report; one per kind per title, or a stuck picture would
 * file one a second.
 */
class PlaybackGlitchReportTest {

    private val reportPath = "/api/v1/admin/diagnostics/playback"

    private fun watching(reports: MutableList<String>, block: suspend (FakePlayer, GlobalPlayerState) -> Unit) = runBlocking {
        var server: StubServer? = null
        val route = { _: HttpExchange ->
            reports += server!!.requestBodies.getValue(reportPath)
            "{}"
        }
        StubServer(mapOf(reportPath to route)).also { server = it }.use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = GlobalPlayerState().apply {
                mediaType = "episode"
                mediaId = "ep-4"
                title = "A Good Girl's Guide to Murder"
            }
            val player = FakePlayer()
            val watch = launch(start = CoroutineStart.UNDISPATCHED) { gps.reportGlitches(player, api) }
            try {
                block(player, gps)
            } finally {
                watch.cancel()
            }
        }
    }

    private suspend fun settle(reports: List<String>, count: Int) {
        withTimeout(5_000) { while (reports.size < count) delay(20) }
        delay(300)
    }

    private fun glitch(kind: Kind) =
        PlaybackGlitch(kind, 1_773_000, "still paused 4000ms on", listOf("-6.0s screen on", "-4.0s play"))

    @Test
    fun `a glitch is reported with what led up to it`() {
        val reports = Collections.synchronizedList(mutableListOf<String>())
        watching(reports) { player, _ ->
            player.glitchFeed.emit(glitch(Kind.PlayIgnored))
            settle(reports, 1)
            val detail = reports.single()
            assertTrue("Playback glitch: play was asked for and the player stayed paused at 1773s" in detail, detail)
            assertTrue("Title: A Good Girl" in detail, detail)
            assertTrue("-6.0s screen on" in detail, detail)
        }
    }

    @Test
    fun `one report per kind per title`() {
        val reports = Collections.synchronizedList(mutableListOf<String>())
        watching(reports) { player, gps ->
            player.glitchFeed.emit(glitch(Kind.PictureFrozen))
            player.glitchFeed.emit(glitch(Kind.PictureFrozen))
            player.glitchFeed.emit(glitch(Kind.ClockRacing))
            settle(reports, 2)
            gps.mediaId = "ep-5"
            player.glitchFeed.emit(glitch(Kind.PictureFrozen))
            settle(reports, 3)
            assertEquals(3, reports.size, "$reports")
        }
    }
}
