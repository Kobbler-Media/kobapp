package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.kobplayer.StreamStoppedShort
import dev.kobbler.kobplayer.TrackInfo
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A paused debrid stream sits on an idle connection that the proxy in front of the server
 * closes after a minute or so. The session behind it is still there; the player only has
 * to open it again. Treating that as a dead release threw the session away, searched,
 * resolved a fresh one — over a full-screen "Searching…" — and opened it on whatever audio
 * the release marked default.
 */
class DebridDropReconnectTest {

    private val show = TvShow(
        id = "show-1", title = "Show", year = 2020, posterUrl = "",
        seasons = listOf(Season(number = 1, episodes = listOf(Episode(id = "ep-1", number = 1, title = "One")))),
    )

    private val frenchFirst = listOf(
        AudioTrackDto(streamIndex = 1, language = "fre", isDefault = true),
        AudioTrackDto(streamIndex = 2, language = "eng"),
    )

    private class Watch(
        val stub: StubServer,
        val player: FakePlayer,
        val gps: GlobalPlayerState,
        val searches: AtomicInteger,
        val resolves: AtomicInteger,
        val deleted: MutableList<String>,
        val preparingSeen: () -> Boolean,
    )

    private fun ready(sid: String) =
        """{"session_id":"$sid","state":"ready","method":"direct_play","is_live":false,"source_readable":true}"""

    private fun watching(
        sessionState: () -> String? = { ready("s1") },
        resolvedAudio: String = "",
        block: suspend CoroutineScope.(Watch) -> Unit,
    ) {
        val searches = AtomicInteger()
        val resolves = AtomicInteger()
        val deleted = CopyOnWriteArrayList<String>()
        val routes = mapOf<String, (HttpExchange) -> String>(
            "/api/v1/admin/stream/resolve" to { _ ->
                resolves.incrementAndGet()
                """{"session_id":"s2","method":"direct_play","direct_url":"/api/v1/admin/stream/s2/play","duration_seconds":3600$resolvedAudio}"""
            },
            "/api/v1/admin/playback/sessions/s1/status" to { _ -> sessionState() ?: "{}" },
            "/api/v1/admin/stream/s1" to { ex -> if (ex.requestMethod == "DELETE") deleted += "s1"; "{}" },
            "/api/v1/admin/stream/s1/outcome" to { _ -> "{}" },
            "/api/v1/admin/stream/s2/outcome" to { _ -> "{}" },
        )
        val codeFor = { path: String, _: String -> if (path.endsWith("/s1/status") && sessionState() == null) 404 else null }
        StubServer(routes, codeFor = codeFor).use { stub ->
            runBlocking {
                val api = KobblerApi(HttpClient(), stub.url, "token")
                val player = FakePlayer().apply { durationMs.value = 3_600_000 }
                val gps = GlobalPlayerState()
                val watcher = launch { gps.watchPlayback(player, api) }
                try {
                    withTimeout(20_000) {
                        val option = debridOption("h1")
                        val claimed = KobblerApi.StreamSession(
                            url = "${stub.url}/api/v1/admin/stream/s1/play", sessionId = "s1",
                            audioTracks = frenchFirst, selectedAudioIndex = 1,
                        )
                        tryPlayTvWithFallback(
                            api = api, player = player, gps = gps, show = show,
                            episodeId = "ep-1", seasonIdx = 0, episodeIdx = 0,
                            search = { searches.incrementAndGet(); found(listOf(option, debridOption("h2"))) },
                            picked = null,
                            onCaching = { _, _ -> error("nothing here needs caching") },
                            claim = { InstantStart(option, claimed) },
                            local = { null },
                        )
                        while (stub.requestBodies["/api/v1/admin/stream/s1/outcome"] == null) delay(20)
                        var preparing = false
                        val sampler = launch { while (true) { preparing = preparing || gps.isPreparing; delay(2) } }
                        try {
                            block(Watch(stub, player, gps, searches, resolves, deleted) { preparing })
                        } finally {
                            sampler.cancel()
                        }
                    }
                } finally {
                    watcher.cancel()
                }
            }
        }
    }

    private suspend fun FakePlayer.dropAfterPauseAt(ms: Long) {
        currentMs.value = ms
        pause()
        play()
        val loads = calls.count { it == "load" }
        error.value = StreamStoppedShort(ms, durationMs.value, live = false)
        while (calls.count { it == "load" } == loads || state.value != dev.kobbler.kobplayer.PlayerState.PLAYING) delay(20)
    }

    private suspend fun FakePlayer.dropWhilePausedAt(ms: Long, gps: GlobalPlayerState) {
        currentMs.value = ms
        pause()
        val loads = calls.count { it == "load" }
        error.value = StreamStoppedShort(ms, durationMs.value, live = false)
        while (calls.count { it == "load" } == loads || gps.isStarting) delay(20)
        delay(100)
    }

    @Test
    fun `a stream that drops after a pause is opened again on the same session, without a search`() = watching { w ->
        w.player.dropAfterPauseAt(600_000)

        assertEquals(0, w.searches.get(), "searched for streams to reopen one that was still there")
        assertEquals(0, w.resolves.get(), "resolved a new session for a release whose session was alive")
        assertEquals(emptyList(), w.deleted.toList(), "deleted the session it should have reopened")
        assertEquals("s1", w.gps.sessionId)
        assertTrue(w.player.loadedUrl?.contains("/s1/") == true, "${w.player.loadedUrl}")
        assertEquals(600, w.player.loadedSource?.startTimeSec, "reopened somewhere other than the playhead")
        assertFalse(w.preparingSeen(), "put the full-screen search shell up over a reconnect")
    }

    @Test
    fun `every pause over a long watch is reconnected, not escalated to another release`() = watching { w ->
        for (minute in listOf(10L, 25L, 40L)) w.player.dropAfterPauseAt(minute * 60_000)

        assertEquals(0, w.resolves.get(), "a later drop was blamed on the release")
        assertEquals("s1", w.gps.sessionId)
        assertEquals(2_400, w.player.loadedSource?.startTimeSec)
    }

    @Test
    fun `a stream that drops after a pause keeps the audio track the viewer was hearing`() = watching { w ->
        w.gps.selectedServerAudioIndex = 2
        w.player.dropAfterPauseAt(600_000)

        assertEquals("eng", w.player.loadedSource?.audioLanguage, "reopened on the release's default audio")
        assertEquals(2, w.gps.selectedServerAudioIndex)
    }

    @Test
    fun `a dropped stream whose session the server no longer has is resolved again`() =
        watching(sessionState = { null }) { w ->
            w.player.dropAfterPauseAt(600_000)

            assertEquals(1, w.resolves.get(), "a session that is gone has to be resolved again")
            assertEquals("s2", w.gps.sessionId)
            assertEquals(listOf("s1"), w.deleted.toList())
        }

    @Test
    fun `a release resolved again after a drop opens on the audio the viewer was hearing, not the server's pick`() =
        watching(sessionState = { null }, resolvedAudio = ",$FrenchDefaultTracks,\"selected_audio_index\":1") { w ->
            w.gps.selectedServerAudioIndex = 2
            w.player.dropAfterPauseAt(600_000)

            assertEquals("s2", w.gps.sessionId)
            assertEquals("eng", w.player.loadedSource?.audioLanguage)
            assertEquals(2, w.gps.selectedServerAudioIndex)
        }

    @Test
    fun `a release resolved again with no tracks yet goes back to the viewer's audio once its probe lands`() =
        watching(sessionState = { null }) { w ->
            w.gps.selectedServerAudioIndex = 2
            w.player.dropAfterPauseAt(600_000)
            w.player.audioTracks.value = listOf(TrackInfo("a1", "French", "fre"), TrackInfo("a2", "English", "eng"))
            w.player.selectedAudioTrack.value = w.player.audioTracks.value.first()

            val api = KobblerApi(HttpClient(), w.stub.url, "token")
            applyDebridSessionFrame(w.gps, api, "s2", probeComplete(preferred = null), w.player)

            assertEquals("a2", w.player.selectedAudioTrack.value?.id, "left the release's default audio playing")
            assertEquals(2, w.gps.selectedServerAudioIndex)
        }

    @Test
    fun `a stream that drops while paused is reopened still paused`() = watching { w ->
        w.player.dropWhilePausedAt(600_000, w.gps)

        assertEquals("s1", w.gps.sessionId)
        assertFalse(w.player.isPlaying.value, "the reconnect started playback the viewer had paused")
        assertEquals("pause", w.player.calls.last { it == "play" || it == "pause" })
    }

    @Test
    fun `a stream resolved again while paused stays paused`() = watching(sessionState = { null }) { w ->
        w.player.dropWhilePausedAt(600_000, w.gps)

        assertEquals("s2", w.gps.sessionId)
        assertFalse(w.player.isPlaying.value, "the re-resolve started playback the viewer had paused")
    }

    @Test
    fun `a release resolved again asks the server for the audio the viewer was hearing`() =
        watching(sessionState = { null }) { w ->
            w.gps.selectedServerAudioIndex = 2
            w.player.dropAfterPauseAt(600_000)

            val body = w.stub.requestBodies["/api/v1/admin/stream/resolve"].orEmpty()
            assertTrue("\"preferred_audio_language\":\"eng\"" in body, "a transcode would pick its own audio: $body")
        }
}
