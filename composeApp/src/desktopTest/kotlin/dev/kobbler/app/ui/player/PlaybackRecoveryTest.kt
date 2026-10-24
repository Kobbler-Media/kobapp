package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.ExperimentalTestApi
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A start that never becomes playback used to be a spinner with nothing behind it.
 *
 * The session had been reaped, or the API had restarted under the playlist, and the only
 * report of it was a decoder error on a flow the UI did not read — so the player sat
 * there, mid-load, forever. Recovery mints a fresh session, reopens the stream where the
 * viewer was, and gives up loudly rather than quietly.
 */
@OptIn(ExperimentalTestApi::class)
class PlaybackRecoveryTest {

    private fun mintingRoutes(minted: MutableList<String>) = mapOf(
        "/api/v1/admin/playback/sessions" to { _: HttpExchange ->
            val id = "s${minted.size + 1}"
            minted += id
            """{"session_id":"$id","direct_url":"http://127.0.0.1:1/$id.mkv","method":"direct"}"""
        },
    )

    private fun watching(sessionId: String? = "dead-1") = GlobalPlayerState().apply {
        mediaType = "movie"
        mediaId = "mov-1"
        this.sessionId = sessionId
        title = "Project Hail Mary"
    }

    private fun stalledPlayer(atMs: Long) = FakePlayer().apply {
        playStalls = true
        durationMs.value = 9_444_000
        currentMs.value = atMs
    }

    @Test
    fun `a lost stream reopens on a fresh session, where the viewer was`() = runTest {
        val minted = CopyOnWriteArrayList<String>()
        StubServer(mintingRoutes(minted)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = watching()
            val player = stalledPlayer(atMs = 90_000)

            assertTrue(gps.recoverPlayback(player, api), "a local movie session must be recoverable")

            assertEquals(listOf("s1"), minted.toList(), "no fresh session was minted")
            assertEquals("s1", gps.sessionId, "the state kept the dead session id")
            assertTrue(
                player.loadedUrl?.endsWith("s1.mkv") == true,
                "the player was never given the new stream: ${player.loadedUrl}",
            )
            assertEquals(
                90, player.loadedSource?.startTimeSec,
                "the reconnect restarted the title from the top",
            )
            assertNull(gps.loadingError, "reported a failure it had just recovered from")
            assertFalse(gps.isRecovering, "left the player wearing its reconnecting shell")
        }
    }

    /** Nothing to reopen is an answer. Spinning on it is not. */
    @Test
    fun `a server with nothing to reopen gives up and says so`() = runTest {
        StubServer(emptyMap()).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = watching()

            gps.recoverPlayback(stalledPlayer(atMs = 0), api)

            assertNotNull(gps.loadingError, "gave up without telling anybody")
            assertFalse(gps.isStarting, "left the loading state up over a dead player")
        }
    }

    /**
     * Minting a session succeeds even when what it points at is unplayable, and a fresh
     * session resets every per-stream retry counter there is. Without a cap, a title that
     * fails the same way each time reconnects for as long as the viewer leaves it up.
     */
    @Test
    fun `reconnecting is capped, then reported`() = runTest {
        val minted = CopyOnWriteArrayList<String>()
        StubServer(mintingRoutes(minted)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = watching()
            val player = stalledPlayer(atMs = 0)

            repeat(MAX_RECOVERIES_IN_WINDOW) { gps.recoverPlayback(player, api) }
            assertNull(gps.loadingError, "gave up inside its own budget")

            gps.recoverPlayback(player, api)
            assertNotNull(gps.loadingError, "kept reconnecting past the budget — that never ends")
            assertEquals(
                MAX_RECOVERIES_IN_WINDOW, minted.size,
                "minted a session it had already decided not to use",
            )
        }
    }

    /** A deliberate press is not part of any storm. */
    @Test
    fun `retry buys a fresh budget`() = runTest {
        val minted = CopyOnWriteArrayList<String>()
        StubServer(mintingRoutes(minted)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = watching()
            val player = stalledPlayer(atMs = 0)

            repeat(MAX_RECOVERIES_IN_WINDOW + 1) { gps.recoverPlayback(player, api) }
            assertNotNull(gps.loadingError)

            gps.retryPlayback(player, api)

            assertNull(gps.loadingError, "the retry button is dead once the budget is spent")
            assertEquals(MAX_RECOVERIES_IN_WINDOW + 1, minted.size, "the retry minted nothing")
        }
    }

    /** A debrid stream comes straight from the provider — there is no local session to mint. */
    @Test
    fun `a debrid stream is not reopened this way`() = runTest {
        val minted = CopyOnWriteArrayList<String>()
        StubServer(mintingRoutes(minted)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = watching().apply { isDebrid = true }

            assertFalse(
                gps.recoverPlayback(stalledPlayer(atMs = 0), api),
                "claimed to have recovered a stream it cannot mint",
            )
            assertTrue(minted.isEmpty(), "asked the library for a session a debrid stream has not got")
        }
    }

    /** Closing the player mid-reconnect ends the reconnect — it does not race it. */
    @Test
    fun `a reconnect abandoned by a stop does not reopen anything`() = runTest {
        val minted = CopyOnWriteArrayList<String>()
        StubServer(mintingRoutes(minted)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = watching()
            val player = stalledPlayer(atMs = 0)
            gps.mediaId = null

            assertFalse(gps.recoverPlayback(player, api), "reconnected a player nobody is watching")
            assertTrue(minted.isEmpty())
        }
    }

    /** Rebuilding the pipeline must not decide to play on the viewer's behalf. */
    @Test
    fun `a session that died under a paused player reopens paused`() = runTest {
        val minted = CopyOnWriteArrayList<String>()
        StubServer(mintingRoutes(minted)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = watching()
            val player = stalledPlayer(atMs = 90_000)

            gps.recoverPlayback(player, api, keepPaused = true)

            assertEquals(listOf("s1"), minted.toList(), "no fresh session was minted")
            assertFalse(
                player.calls.contains("play"),
                "started a player the viewer had paused: ${player.calls}",
            )
            assertFalse(gps.isStarting, "left the loading state up over a player it will not start")
        }
    }

    /**
     * Both heartbeats are keyed on the session id the reconnect they start replaces, so a
     * reconnect run inside one cancels itself between stopping the player and reopening it.
     */
    @Test
    fun `a session reaped under a paused player is reopened by the heartbeat`() {
        val minted = CopyOnWriteArrayList<String>()
        val routes = mintingRoutes(minted) +
            ("/api/v1/admin/playback/sessions/s1/heartbeat" to { _: HttpExchange -> "{}" })
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = watching()
            val player = stalledPlayer(atMs = 90_000)

            reapedUnder(gps, player) { PlaybackSessionHeartbeat(gps, player, api, intervalMs = 50) }

            assertEquals("s1", gps.sessionId)
        }
    }

    /**
     * A channel has no end, so the player has to know it is playing one: a stop it was not
     * asked for is then a dropout to reconnect, never the end of a title.
     */
    @Test
    fun `channels are opened as live and titles are not`() = runTest {
        val routes = mintingRoutes(CopyOnWriteArrayList()) + mapOf(
            "/api/v1/admin/livetv/sessions" to { _: HttpExchange ->
                """{"session_id":"live-2","channel_id":"chan-1","method":"direct","direct_url":"http://127.0.0.1:1/live-2.ts"}"""
            },
        )
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            for ((type, id, live) in listOf(Triple("livetv", "chan-1", true), Triple("movie", "mov-1", false))) {
                val gps = GlobalPlayerState().apply {
                    mediaType = type
                    mediaId = id
                    title = "Watching"
                }
                val player = stalledPlayer(atMs = 60_000)
                assertTrue(gps.recoverPlayback(player, api), type)
                assertEquals(live, player.loadedSource?.live, "$type opened live=${player.loadedSource?.live}")
            }
        }
    }

    @Test
    fun `a lease the server let go is retuned by the live heartbeat`() {
        val routes = mapOf(
            "/api/v1/admin/livetv/sessions" to { _: HttpExchange ->
                """{"session_id":"live-2","channel_id":"chan-1","method":"direct","direct_url":"http://127.0.0.1:1/live-2.ts"}"""
            },
            "/api/v1/admin/livetv/sessions/live-2/heartbeat" to { _: HttpExchange -> "{}" },
        )
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = GlobalPlayerState().apply {
                mediaType = "livetv"
                mediaId = "chan-1"
                liveSessionId = "live-1"
                title = "ABC"
            }
            val player = stalledPlayer(atMs = 0)

            reapedUnder(gps, player) { LiveSessionHeartbeat(gps, api, player, intervalMs = 50) }

            assertEquals("live-2", gps.liveSessionId)
        }
    }

    private fun reapedUnder(
        gps: GlobalPlayerState,
        player: FakePlayer,
        heartbeat: @androidx.compose.runtime.Composable () -> Unit,
    ) = runUiTest {
        setContent { heartbeat() }
        waitUntil(timeoutMillis = UiTimeoutMs) { player.calls.contains("stop") && !gps.isRecovering }
        assertTrue(player.calls.contains("load"), "stopped the player and never reopened it: ${player.calls}")
    }

    /**
     * The whole point: a start that never happens reaches the reconnect rather than
     * nothing at all — and through the probe, so a session the server has already lost
     * costs one request rather than a whole budget.
     */
    @Test
    fun `a start that never plays is reconnected`() {
        val minted = CopyOnWriteArrayList<String>()
        StubServer(mintingRoutes(minted)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = watching()
            val player = stalledPlayer(atMs = 0)
            gps.isStarting = true

            runBlocking {
                val watcher = launch(Dispatchers.Default) { gps.watchPlayback(player, api) }
                gps.startRequests.subscriptionCount.first { it > 0 }
                gps.playUnmuted(player)
                withTimeout(10_000) { while (minted.isEmpty()) delay(20) }
                watcher.cancel()
            }
            assertEquals(listOf("s1"), minted.toList())
        }
    }

    /** The player only ever knows it could not open what it was given — try again first. */
    @Test
    fun `a player failure reconnects before it is reported`() {
        val minted = CopyOnWriteArrayList<String>()
        StubServer(mintingRoutes(minted)).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val gps = watching()
            val player = stalledPlayer(atMs = 0)

            runBlocking {
                val watcher = launch(Dispatchers.Default) { gps.watchPlayback(player, api) }
                player.error.value = RuntimeException("VLC playback error")
                
                withTimeout(10_000) { while (minted.isEmpty()) delay(20) }
                watcher.cancel()
            }
            assertNull(gps.loadingError, "reported a failure it could have reconnected through")
        }
    }

    /** Only the server saying it has no such session is a death; a request that never arrived is not. */
    @Test
    fun `only a session the server has forgotten reads as gone`() = runBlocking {
        StubServer(
            mapOf("/api/v1/admin/playback/sessions/live-1/heartbeat" to { _: HttpExchange -> "{}" }),
        ).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            assertFalse(api.sessionGone("live-1"))
            assertTrue(api.sessionGone("reaped-1"))
        }
        val unreachable = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")
        assertFalse(unreachable.sessionGone("live-1"), "a network blip read as a reaped session")
    }
}
