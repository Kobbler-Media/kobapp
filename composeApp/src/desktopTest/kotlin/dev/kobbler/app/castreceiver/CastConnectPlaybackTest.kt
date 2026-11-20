package dev.kobbler.app.castreceiver

import androidx.compose.ui.test.ExperimentalTestApi
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.CastDevice
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.platform.CastFeature
import dev.kobbler.app.platform.CastLoad
import dev.kobbler.app.platform.CastSender
import dev.kobbler.app.platform.SessionFeatures
import dev.kobbler.app.platform.parseCastEvent
import dev.kobbler.app.runUiTest
import dev.kobbler.app.ui.player.CastHandoff
import dev.kobbler.app.ui.player.FakePlayer
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.reportProgress
import dev.kobbler.app.data.CastInitSession
import dev.kobbler.app.data.CastMetadata
import dev.kobbler.app.data.ClientCapabilities
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SessionJson =
    """{"session_id":"tv-session","direct_url":"/api/v1/admin/playback/sessions/tv-session/direct","method":"direct_play","duration_seconds":7200,
       "subtitles":[{"track_id":"en","stream_index":3,"language":"en","url":"/api/v1/admin/playback/sessions/tv-session/subtitles/en.vtt","format":"vtt"},
                    {"track_id":"fr","stream_index":4,"language":"fr","url":"/api/v1/admin/playback/sessions/tv-session/subtitles/fr.vtt","format":"vtt"}],
       "audio_tracks":[{"stream_index":1,"language":"en"},{"stream_index":2,"language":"ja"}],
       "skip_segments":[{"segment_type":"intro","start_seconds":1370,"end_seconds":1450}]}"""

/** The TV app as the receiver: its own player and player state, driven by a cast session. */
@OptIn(ExperimentalTestApi::class)
class CastConnectPlaybackTest {

    private val routes = mapOf(
        "/api/v1/admin/playback/sessions" to SessionJson,
        "/api/v1/admin/playback/sessions/tv-session" to "{}",
        "/api/v1/admin/movies/movie-1/watch-state/position" to "{}",
        "/api/v1/admin/movies/tv-movie/watch-state/position" to "{}",
        "/api/v1/admin/playback/tokens" to """{"token":"cast-tok"}""",
        "/api/v1/library/stats" to """{"kobload_configured":false}""",
        "/api/v1/admin/config" to """{"cast_receiver_app_id":"386D876F"}""",
    )

    @Test
    fun `the cast opens in the TV's own fullscreen player on a session made as the sender`() {
        SeenServer(routes).use { server ->
            val tvApi = KobblerApi(HttpClient(), server.url, "tv-login")
            val tv = TvReceiver(tvApi)
            assertTrue(tv.load(server.url))

            val create = server.seen.single { it.path == "/api/v1/admin/playback/sessions" }
            assertEquals("sender-tok", create.token)
            assertNull(create.authorization, "the session was opened with the TV's login")
            assertEquals("movie-1", tv.gps.mediaId)
            assertTrue(tv.gps.isFullscreen)
            assertEquals(1_380, tv.player.loadedSource?.startTimeSec)
            assertTrue("play" in tv.player.calls)
        }
    }

    /** What the scaffold hands the player: whose progress a save lands on. */
    @Test
    fun `progress is saved as the sender, and the TV's own titles as the TV`() {
        SeenServer(routes).use { server ->
            val tvApi = KobblerApi(HttpClient(), server.url, "tv-login")
            val tv = TvReceiver(tvApi)
            tv.load(server.url)

            runBlocking {
                val castApi = tv.session.apiFor(tv.gps.mediaId) ?: tvApi
                tv.gps.reportProgress(castApi, "movie", "movie-1", 1_500_000)
            }
            val castSave = server.seen.last()
            assertEquals("/api/v1/admin/movies/movie-1/watch-state/position", castSave.path)
            assertEquals("sender-tok", castSave.token)
            assertNull(castSave.authorization)

            assertNull(tv.session.apiFor("tv-movie"), "a title the TV opened itself would save as the sender")
            runBlocking { tv.gps.reportProgress(tv.session.apiFor("tv-movie") ?: tvApi, "movie", "tv-movie", 60_000) }
            assertEquals("Bearer tv-login", server.seen.last().authorization)
        }
    }

    @Test
    fun `commands move the TV's player`() {
        SeenServer(routes).use { server ->
            val tv = TvReceiver(KobblerApi(HttpClient(), server.url, "tv-login"))
            tv.load(server.url)
            runBlocking(Dispatchers.Main) {
                tv.session.seek(3_600_000)
                tv.session.pause()
                tv.session.message("""{"type":"SUBTITLE_CHANGE","payload":{"trackId":2}}""")
            }
            assertTrue("seek:3600000" in tv.player.calls, tv.player.calls.toString())
            assertTrue("pause" in tv.player.calls)
            assertEquals("fr", tv.gps.selectedServerSubtitleTrackId)

            val now = tv.playback.now()
            assertEquals(2, now.tracks.activeSubtitleId)
            assertEquals(listOf(1, 2), now.tracks.audioTracks.map { it.streamIndex })
            assertEquals(3_600_000, now.positionMs)
        }
    }

    @Test
    fun `the TV reports the skip segment it is in`() {
        SeenServer(routes).use { server ->
            val tv = TvReceiver(KobblerApi(HttpClient(), server.url, "tv-login"))
            tv.load(server.url)
            assertEquals(dev.kobbler.app.data.CastSkip("intro", 1_450.0), tv.playback.now().skip)
        }
    }

    /**
     * The phone's handoff, unchanged, against kobapp as the receiver: what one says the
     * other has to act on.
     */
    @Test
    fun `the phone's cast handoff drives kobapp on the TV`() = runUiTest {
        SeenServer(routes).use { server ->
            val phoneApi = KobblerApi(HttpClient(), server.url, "phone-login")
            val tv = TvReceiver(KobblerApi(HttpClient(), server.url, "tv-login"))
            val sender = LoopbackSender(tv)
            val phoneGps = GlobalPlayerState()
            val phonePlayer = FakePlayer().also { it.durationMs.value = 7_200_000 }
            runBlocking {
                val url = "${server.url}/api/v1/admin/playback/sessions/phone-own/stream"
                phoneGps.load(
                    player = phonePlayer, api = phoneApi, type = "movie", id = "movie-1", streamUrl = url,
                    newSessionId = "phone-own", newTitle = "Film", newSubtitle = null, newPosterUrl = null,
                    newLogoUrl = null, destination = null,
                    session = KobblerApi.StreamSession(url = url, sessionId = "phone-own", method = "direct_play", durationSeconds = 7_200.0),
                )
            }
            phoneGps.isFullscreen = true
            phonePlayer.currentMs.value = 1_380_000

            setContent { CastHandoff(sender, phoneGps, phoneApi, phonePlayer) }
            waitUntil(timeoutMillis = UiTimeoutMs) { sender.collecting }
            sender.device.value = CastDevice("Living Room TV")

            waitUntil(timeoutMillis = UiTimeoutMs) { tv.gps.mediaId == "movie-1" }
            assertEquals(1_380, tv.player.loadedSource?.startTimeSec)
            val tvCreate = server.seen.last { it.path == "/api/v1/admin/playback/sessions" }
            assertEquals("cast-tok", tvCreate.token, "the TV did not use the token the phone handed it")

            waitUntil(timeoutMillis = UiTimeoutMs) { phoneGps.castSession?.tracks?.subtitles?.size == 2 }

            tv.player.currentMs.value = 2_000_000
            runOnUiThread { tv.session.observe(tv.playback.now()) }
            waitUntil(timeoutMillis = UiTimeoutMs) { phoneGps.castSession?.positionMs == 2_000_000L }

            phoneGps.onCastSeek?.invoke(3_000_000L)
            waitUntil(timeoutMillis = UiTimeoutMs) { "seek:3000000" in tv.player.calls }

            phoneGps.onCastSubtitle?.invoke(1)
            waitUntil(timeoutMillis = UiTimeoutMs) { tv.gps.selectedServerSubtitleTrackId == "en" }

            runOnUiThread { tv.gps.stop(tv.player, tv.session.apiFor("movie-1")!!, tv.gps.teardownScope) }
            runOnUiThread { tv.session.observe(tv.playback.now()) }
            waitUntil(timeoutMillis = UiTimeoutMs) { sender.stopped }
        }
    }
}

internal class TvReceiver(tvApi: KobblerApi) {
    val gps = GlobalPlayerState()
    val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
    val playback = GpsReceiverPlayback(gps, player) { tvApi }
    val sent = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val session = CastReceiverSession(
        playback = playback,
        send = { sent.tryEmit(it) },
        capabilities = { tvApi.capabilities() },
        scope = CoroutineScope(Dispatchers.Main),
    )

    fun load(baseUrl: String, positionSec: Int = 1_380) = runBlocking(Dispatchers.Main) {
        session.load(
            ReceiverLoad(
                CastInitSession(
                    baseUrl = baseUrl, token = "sender-tok", sessionId = "phone-session", mediaId = "movie-1",
                    mediaType = "movie", playlistUrl = null, directUrl = null, method = "direct_play", duration = 7_200.0,
                    capabilities = ClientCapabilities(listOf("h264"), listOf("aac"), listOf("mp4")),
                    metadata = CastMetadata(title = "Film"),
                ),
                positionSec,
            ),
        )
    }
}

/** The phone's sender wired straight into the TV's receiver session, as the wire would. */
internal class LoopbackSender(private val tv: TvReceiver) : CastSender {
    override val device = MutableStateFlow<CastDevice?>(null)
    override val events = MutableSharedFlow<CastEvent>(extraBufferCapacity = 64)
    private val session = SessionFeatures(CastFeature.entries.toSet())
    override val features get() = session.current
    @Volatile var collecting = false
    @Volatile var stopped = false

    init {
        CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            tv.sent.collect { json ->
                parseCastEvent(json)?.let {
                    session.heard(it)
                    events.emit(it)
                }
            }
        }
    }

    /** What the Cast Connect SDK tells the TV once this sender has joined. */
    fun senderJoined() = runBlocking(Dispatchers.Main) { tv.session.senderConnected() }

    override fun start() { collecting = true }

    override suspend fun load(load: CastLoad) {
        val parsed = parseReceiverLoad("""{"kobblerSession":${load.payloadJson}}""", load.startPositionSec.toDouble())!!
        withContext(Dispatchers.Main) { tv.session.load(parsed) }
    }

    override suspend fun send(type: String, payloadJson: String) {
        val message = buildJsonObject {
            put("type", type)
            if (payloadJson.isNotBlank()) put("payload", Json.parseToJsonElement(payloadJson))
        }.toString()
        withContext(Dispatchers.Main) { tv.session.message(message) }
    }

    override suspend fun seekTo(positionSeconds: Int) = withContext(Dispatchers.Main) { tv.session.seek(positionSeconds * 1000L) }

    override suspend fun togglePlayPause() = withContext(Dispatchers.Main) {
        if (tv.playback.now().playing) tv.session.pause() else tv.session.play()
    }

    override suspend fun stop() {
        stopped = true
        device.value = null
        withContext(Dispatchers.Main) { tv.session.stop() }
    }
}
