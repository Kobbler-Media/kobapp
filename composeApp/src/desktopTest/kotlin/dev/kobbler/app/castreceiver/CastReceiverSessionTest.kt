package dev.kobbler.app.castreceiver

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.ApiException
import dev.kobbler.app.data.CastInitSession
import dev.kobbler.app.data.CastMetadata
import dev.kobbler.app.data.CastSkip
import dev.kobbler.app.data.ClientCapabilities
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.platform.parseCastEvent
import dev.kobbler.app.ui.player.StubServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a request reached the server with: the path, its `token` query, and any header login. */
data class Seen(val method: String, val path: String, val token: String?, val authorization: String?)

class SeenServer(routes: Map<String, String>, codes: Map<String, Int> = emptyMap()) : AutoCloseable {
    val seen = CopyOnWriteArrayList<Seen>()
    private val stub = StubServer(
        routes.mapValues { (_, body) ->
            { exchange: HttpExchange ->
                seen += Seen(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestURI.query?.split('&')?.firstNotNullOfOrNull { it.removePrefix("token=").takeIf { v -> v != it } },
                    exchange.requestHeaders.getFirst("Authorization"),
                )
                body
            }
        },
        codes = codes,
    )
    val url get() = stub.url
    override fun close() = stub.close()
}

internal suspend fun eventually(what: String, check: () -> Boolean) {
    withTimeout(10_000) { while (!check()) delay(10) }.also { assertTrue(check(), what) }
}

class CastReceiverSessionTest {

    private val caps = ClientCapabilities(listOf("h264", "hevc"), listOf("aac"), listOf("mp4", "mkv"))

    private fun load(baseUrl: String, mediaType: String = "movie", mediaId: String = "movie-1", positionSec: Int = 1_380, token: String = "sender-tok") =
        ReceiverLoad(
            CastInitSession(
                baseUrl = baseUrl, token = token, sessionId = "phone-session", mediaId = mediaId, mediaType = mediaType,
                playlistUrl = null, directUrl = null, method = "direct_play", duration = 7_200.0,
                capabilities = caps, metadata = CastMetadata(title = "Film"),
            ),
            positionSec,
        )

    private class Harness(val playback: FakePlayback = FakePlayback()) {
        val sent = CopyOnWriteArrayList<String>()
        var clock = 0L
        val session = CastReceiverSession(
            playback = playback,
            send = { sent += it },
            capabilities = { ClientCapabilities(listOf("h264", "hevc"), listOf("aac", "eac3"), listOf("mp4", "mkv")) },
            scope = CoroutineScope(Dispatchers.Unconfined),
            clockMs = { clock },
        )
        val events get() = sent.mapNotNull { parseCastEvent(it) }
    }

    @Test
    fun `a load opens the title at the sender's position, speaking as the sender`() = runBlocking {
        SeenServer(mapOf("/api/v1/admin/movies/movie-1/watch-state/position" to "{}")).use { server ->
            val h = Harness()
            assertTrue(h.session.load(load(server.url)))

            assertEquals(listOf(ReceiverTitle("movie", "movie-1", "Film", null, null) to 1_380), h.playback.opened)
            val api = h.playback.openedWith!!
            assertEquals(server.url, api.apiUrl)
            api.reportMovieProgress("movie-1", 1_400)
            val save = server.seen.single()
            assertEquals("sender-tok", save.token)
            assertNull(save.authorization, "a header login went out with the sender's save")
        }
    }

    @Test
    fun `the TV's own title is put away before the cast takes the player`() = runBlocking {
        val h = Harness()
        h.playback.now = h.playback.now.copy(mediaType = "movie", mediaId = "tv-own", playing = true)
        h.session.load(load("http://unused"))
        assertEquals(listOf("releaseLocal"), h.playback.calls)
    }

    @Test
    fun `transport and track commands reach the player`() = runBlocking {
        val h = Harness()
        h.session.load(load("http://unused"))
        h.session.seek(3_600_000)
        h.session.pause()
        h.session.play()
        h.session.message("""{"type":"SUBTITLE_CHANGE","payload":{"trackId":2}}""")
        h.session.message("""{"type":"SUBTITLE_CHANGE","payload":{"trackId":null}}""")
        h.session.message("""{"type":"AUDIO_SWITCH","payload":{"streamIndex":3}}""")
        assertEquals(
            listOf("seek 3600000", "pause", "play", "subtitle 2", "subtitle null", "audio 3"),
            h.playback.calls,
        )
        assertEquals(3, h.events.count { it is CastEvent.TracksChanged }, "a track change goes unanswered")
    }

    @Test
    fun `requests are answered`() = runBlocking {
        val h = Harness()
        h.session.message("""{"type":"CAPABILITIES_REQUEST"}""")
        assertEquals(CastEvent.Capabilities(ClientCapabilities(listOf("h264", "hevc"), listOf("aac", "eac3"), listOf("mp4", "mkv"))), h.events.single())
        h.session.load(load("http://unused"))
        h.sent.clear()
        h.session.message("""{"type":"STATUS_REQUEST"}""")
        h.session.message("""{"type":"TRACKS_REQUEST"}""")
        val (status, tracks) = h.events
        assertEquals("movie-1", (status as CastEvent.NowPlaying).mediaId)
        assertTrue(tracks is CastEvent.TracksChanged)
    }

    @Test
    fun `a sender that attaches is told what this device decodes`() = runBlocking {
        val h = Harness()
        h.session.senderConnected()
        assertTrue(h.events.single() is CastEvent.Capabilities)
    }

    @Test
    fun `audiobooks and read-alouds are not taken`() = runBlocking {
        val h = Harness()
        h.session.message("""{"type":"INIT_AUDIOBOOK","payload":{}}""")
        h.session.message("""{"type":"INIT_TTS","payload":{}}""")
        assertTrue(h.playback.opened.isEmpty())
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `what the player does is reported back`() = runBlocking {
        val h = Harness()
        h.session.load(load("http://unused"))
        val playing = h.playback.now.copy(positionMs = 1_381_000, durationMs = 7_200_000)

        h.session.observe(playing)
        assertEquals(CastEvent.Position(1_381.0, 7_200.0, isPlaying = true), h.events.last())

        h.clock += 1_000
        h.session.observe(playing.copy(positionMs = 1_382_000))
        assertEquals(1, h.events.count { it is CastEvent.Position }, "a broadcast every half second")

        h.clock += 5_000
        h.session.observe(playing.copy(positionMs = 1_387_000))
        assertEquals(CastEvent.Position(1_387.0, 7_200.0, isPlaying = true), h.events.last())

        h.session.observe(playing.copy(positionMs = 1_387_000, playing = false))
        assertEquals(CastEvent.Position(1_387.0, 7_200.0, isPlaying = false), h.events.last())

        h.session.observe(playing.copy(skip = CastSkip("credits", 7_150.0), playing = false))
        h.session.observe(playing.copy(skip = CastSkip("credits", 7_150.0), playing = false))
        assertEquals(1, h.events.count { it == CastEvent.SkipChanged(CastSkip("credits", 7_150.0)) })
    }

    /** The viewer pressed BACK on the TV: the phone has nothing left to follow. */
    @Test
    fun `the TV stopping ends the cast`() = runBlocking {
        val h = Harness()
        h.session.load(load("http://unused"))
        h.session.observe(h.playback.now.copy(mediaId = null, mediaType = null, playing = false))
        assertEquals(CastEvent.Finished, h.events.last())
        assertFalse(h.session.isActive)
        assertNull(h.session.apiFor("movie-1"))
    }

    /** The phone takes the watch back itself; a MEDIA_FINISHED would make it stop its own player. */
    @Test
    fun `the sender stopping stops the TV without claiming the title ended`() = runBlocking {
        val h = Harness()
        h.session.load(load("http://unused"))
        h.session.stop()
        assertEquals(listOf("stop"), h.playback.calls)
        assertFalse(CastEvent.Finished in h.events)
        assertFalse(h.session.isActive)
    }

    @Test
    fun `a finished episode goes on to the next as the sender`() = runBlocking {
        val next = """{"episode_id":"ep-2","title":"Second","episode_identifier":"2","season_identifier":"1","show_title":"Show"}"""
        SeenServer(mapOf("/api/v1/admin/tv/episodes/ep-1/next" to next)).use { server ->
            val h = Harness()
            h.session.load(load(server.url, mediaType = "episode", mediaId = "ep-1"))
            h.playback.onFinished!!()
            eventually("never went on") { h.playback.opened.size == 2 }

            assertEquals(ReceiverTitle("episode", "ep-2", "Show", "S1 E2 • Second", null) to 0, h.playback.opened.last())
            eventually("the phone was not told") { CastEvent.EpisodeChanged("ep-2", "Show", "S1 E2 • Second") in h.events }
            assertEquals("sender-tok", server.seen.single().token)
            assertTrue(h.session.apiFor("ep-2") != null, "the next episode's progress would go to the TV's login")
        }
    }

    @Test
    fun `the last episode finishes the cast`() = runBlocking {
        SeenServer(mapOf("/api/v1/admin/tv/episodes/ep-9/next" to "{}"), codes = mapOf("/api/v1/admin/tv/episodes/ep-9/next" to 404)).use { server ->
            val h = Harness()
            h.session.load(load(server.url, mediaType = "episode", mediaId = "ep-9"))
            h.playback.onFinished!!()
            eventually("never finished") { CastEvent.Finished in h.events }
            assertEquals(listOf("stop"), h.playback.calls)
            assertFalse(h.session.isActive)
        }
    }

    @Test
    fun `an expired credential is asked for again and the load retried with the new one`() = runBlocking {
        val h = Harness()
        h.playback.openFails = ApiException(401, "Unauthorized")
        assertFalse(h.session.load(load("http://unused")))
        assertEquals(CastEvent.Expired, h.events.single())

        h.playback.openFails = null
        h.session.message("""{"type":"TOKEN_REFRESH","payload":{"token":"fresh"}}""")
        assertEquals(1, h.playback.opened.size)
        assertTrue(h.session.isActive)
    }

    @Test
    fun `a server that will not open the title leaves no cast behind`() = runBlocking {
        val h = Harness()
        h.playback.openFails = ApiException(500, "Internal Server Error")
        assertTrue(runCatching { h.session.load(load("http://unused")) }.isFailure)
        assertFalse(h.session.isActive)
        assertNull(h.session.apiFor("movie-1"))
    }

    @Test
    fun `a refreshed credential is what later requests carry`() = runBlocking {
        SeenServer(mapOf("/api/v1/admin/movies/movie-1/watch-state/position" to "{}")).use { server ->
            val h = Harness()
            h.session.load(load(server.url))
            h.session.message("""{"type":"TOKEN_REFRESH","payload":{"token":"fresh"}}""")
            h.playback.openedWith!!.reportMovieProgress("movie-1", 1_500)
            assertEquals("fresh", server.seen.single().token)
        }
    }
}
