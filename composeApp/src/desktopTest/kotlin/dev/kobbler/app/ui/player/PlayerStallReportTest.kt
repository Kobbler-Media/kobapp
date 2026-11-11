package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.kobplayer.PlayerStall
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
 * A player stuck in a native call is escaped onto a fresh one, which the viewer may barely
 * notice: without a report, a cause that keeps doing it would go unseen.
 */
class PlayerStallReportTest {

    private val reportPath = "/api/v1/admin/diagnostics/playback"

    private fun watching(reports: MutableList<String>, block: suspend (FakePlayer) -> Unit) = runBlocking {
        var server: StubServer? = null
        val route = { _: HttpExchange ->
            reports += server!!.requestBodies.getValue(reportPath)
            "{}"
        }
        StubServer(mapOf(reportPath to route)).also { server = it }.use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = GlobalPlayerState().apply {
                mediaType = "episode"
                mediaId = "ep-1"
                title = "Ludwig"
            }
            val player = FakePlayer()
            val watch = launch(start = CoroutineStart.UNDISPATCHED) { gps.reportStalls(player, api) }
            try {
                block(player)
            } finally {
                watch.cancel()
            }
        }
    }

    private suspend fun settle(reports: List<String>, count: Int) {
        withTimeout(5_000) { while (reports.size < count) delay(20) }
        delay(300)
    }

    @Test
    fun `a player given up on is reported with where it was stuck`() {
        val reports = Collections.synchronizedList(mutableListOf<String>())
        watching(reports) { player ->
            player.stallFeed.emit(PlayerStall(1_600, "at uk.co.caprica.vlcj.player.base.MediaPlayerStopLatch.stop"))
            settle(reports, 1)
            val detail = reports.single()
            assertTrue("Player stuck: a native player call ran 1600ms" in detail, detail)
            assertTrue("MediaPlayerStopLatch.stop" in detail, detail)
            assertTrue("Title: Ludwig" in detail, detail)
        }
    }

    @Test
    fun `a stuck player that stays stuck is reported again, one released is not`() {
        val reports = Collections.synchronizedList(mutableListOf<String>())
        watching(reports) { player ->
            val stall = PlayerStall(1_600, "at somewhere")
            player.stallFeed.emit(stall)
            player.stallFeed.emit(stall.copy(released = true))
            player.stallFeed.emit(stall.copy(released = false))
            settle(reports, 2)
            assertEquals(2, reports.size, "$reports")
            assertTrue("still stuck" in reports[1], reports[1])
        }
    }
}
