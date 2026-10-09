package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.kobplayer.PlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The branding held over a stream that has not started is where a slow start is felt, and
 * a stale prewarm reconnecting under it left nothing behind to say why. Past a few seconds
 * the start is reported on its own, with what it went through, however it ends.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SlowStartReportTest {

    private val reportPath = "/api/v1/admin/diagnostics/playback"

    private fun routes(minted: MutableList<String>) = mapOf(
        reportPath to { _: HttpExchange -> "{}" },
        "/api/v1/admin/playback/sessions" to { _: HttpExchange ->
            val id = "s${minted.size + 1}"
            minted += id
            """{"session_id":"$id","direct_url":"http://127.0.0.1:1/$id.mkv","method":"direct"}"""
        },
    )

    private fun watching() = GlobalPlayerState().apply {
        mediaType = "episode"
        mediaId = "ep-1"
        sessionId = "prewarm-1"
        title = "Ludwig"
    }

    @Test
    fun `a start that plays promptly files nothing`() = runTest {
        StubServer(routes(mutableListOf())).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer()
            val watch = async { watching().watchSlowStart(player, api, now = { testScheduler.currentTime }) }
            delay(2_000)
            player.state.value = PlayerState.PLAYING

            assertNull(watch.await())
            assertNull(stub.requestBodies[reportPath])
        }
    }

    @Test
    fun `a start that recovers late is reported with the reconnect behind it`() = runTest {
        StubServer(routes(mutableListOf())).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = watching()
            val player = FakePlayer().apply { playStalls = true }
            val watch = async { gps.watchSlowStart(player, api, now = { testScheduler.currentTime }) }

            // The reconnect itself is covered below; here its real HTTP would let the
            // virtual clock run the watch out before it landed.
            delay(1_000)
            gps.noteStartEvent("reconnect: stream dropped while paused")
            delay(7_900)
            player.state.value = PlayerState.PLAYING

            val detail = assertNotNull(watch.await())
            assertTrue(detail.startsWith("Slow start"), detail)
            assertTrue("started after 9.0s" in detail, detail)
            assertTrue("reconnect: stream dropped while paused" in detail, detail)
            assertTrue("Title: Ludwig" in detail, detail)
            assertEquals(true, stub.requestBodies[reportPath]?.contains("Slow start"))
        }
    }

    @Test
    fun `a reconnect is recorded with its cause and travels with any failure report`() = runTest {
        val minted = mutableListOf<String>()
        StubServer(routes(minted)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = watching()
            gps.recoverPlayback(FakePlayer().apply { playStalls = true }, api, cause = IllegalStateException("stream dropped while paused"))

            assertEquals(listOf("s1"), minted.toList())
            assertEquals(listOf("reconnect: stream dropped while paused"), gps.startEvents)
            assertTrue(gps.diagnosticContext().any { "reconnect: stream dropped while paused" in it })
        }
    }

    @Test
    fun `a start that never plays is reported once the wait runs out`() = runTest {
        StubServer(routes(mutableListOf())).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().apply { state.value = PlayerState.BUFFERING }

            val detail = assertNotNull(watching().watchSlowStart(player, api, now = { testScheduler.currentTime }))
            assertTrue("still not playing after 36.0s" in detail, detail)
            assertTrue("Player: BUFFERING" in detail, detail)
        }
    }
}
