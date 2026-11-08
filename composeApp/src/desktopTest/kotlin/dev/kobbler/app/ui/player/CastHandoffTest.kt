package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.ExperimentalTestApi
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.CastAudioTrack
import dev.kobbler.app.data.CastSkip
import dev.kobbler.app.data.CastTextTrack
import dev.kobbler.app.data.CastTracks
import dev.kobbler.app.data.ClientCapabilities
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.CastDevice
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.platform.CastLoad
import dev.kobbler.app.platform.CastSender
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A cast session is the same watch continued elsewhere, and the transport on screen is
 * the only thing the viewer can reach it with — so what it shows has to be what the
 * receiver is doing, and what they do to it has to arrive.
 */
@OptIn(ExperimentalTestApi::class)
class CastHandoffTest {

    private val routes = mapOf(
        "/api/v1/admin/playback/tokens" to { _: HttpExchange -> """{"token":"cast-tok"}""" },
        "/api/v1/library/stats" to { _: HttpExchange -> """{"kobload_configured":false}""" },
        "/api/v1/admin/config" to { _: HttpExchange -> """{"cast_receiver_app_id":"ABC123"}""" },
        "/api/v1/admin/movies/movie-1/progress" to { _: HttpExchange -> "{}" },
    )

    private fun GlobalPlayerState.open(
        api: KobblerApi,
        player: FakePlayer,
        id: String,
        session: String,
        atSec: Int = 0,
        lengthSec: Double = 0.0,
    ) =
        runBlocking {
            val url = "http://host/api/v1/admin/playback/sessions/$session/stream"
            load(
                player = player, api = api,
                type = "movie", id = id,
                streamUrl = url,
                newSessionId = session,
                newTitle = "Film $id", newSubtitle = null,
                newPosterUrl = null, newLogoUrl = null,
                destination = null,
                seekToSec = atSec,
                session = KobblerApi.StreamSession(url = url, sessionId = session, method = "direct_play", durationSeconds = lengthSec),
            )
        }

    private fun loaded(api: KobblerApi, player: FakePlayer): GlobalPlayerState {
        val gps = GlobalPlayerState()
        gps.open(api, player, "movie-1", "sess-1")
        gps.isFullscreen = true
        return gps
    }

    @Test
    fun `connecting moves the watch to the receiver and stops it here`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            player.currentMs.value = 1_380_000
            val cast = FakeSender()

            setContent { CastHandoff(cast, gps, api, player) }
            waitUntil(timeoutMillis = UiTimeoutMs) { cast.started }

            cast.connect("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession != null }

            assertEquals(1_380, cast.loads.single().startPositionSec)
            assertTrue("pause" in player.calls, "still playing here as well: ${player.calls}")
            val session = assertNotNull(gps.castSession)
            assertEquals("Living Room TV", session.deviceName)
            assertEquals(1_380_000L, session.positionMs)
            assertEquals(7_200_000L, session.durationMs)
        }
    }

    @Test
    fun `the transport reaches the receiver`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            val cast = FakeSender()

            setContent { CastHandoff(cast, gps, api, player) }
            cast.connect("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession != null }

            gps.onCastPlayPause?.invoke()
            gps.onCastSeek?.invoke(3_600_000L)
            waitUntil(timeoutMillis = UiTimeoutMs) { cast.seeks.isNotEmpty() && cast.toggles > 0 }

            assertEquals(listOf(3_600), cast.seeks)
            // Shown as moved straight away: the seek is a round trip, and the bar answers
            // to the hand on it.
            assertEquals(3_600_000L, gps.castSession?.positionMs)
        }
    }

    /**
     * The receiver broadcasts every five seconds, so one already in flight when the
     * viewer lets go carries the position they scrubbed away from.
     */
    @Test
    fun `a broadcast that predates the scrub does not drag the bar back`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            val cast = FakeSender()

            setContent { CastHandoff(cast, gps, api, player) }
            cast.connect("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession != null }

            runBlocking { cast.say(CastEvent.Position(600.0, 7_200.0, isPlaying = true)) }
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession?.positionMs == 600_000L }

            gps.onCastSeek?.invoke(3_600_000L)
            runBlocking { cast.say(CastEvent.Position(605.0, 7_200.0, isPlaying = true)) }
            waitForIdle()

            assertEquals(
                3_600_000L, gps.castSession?.positionMs,
                "the scrub was undone by a position the receiver sent before it",
            )
        }
    }

    @Test
    fun `ending a cast picks the watch up where the receiver left it`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            val cast = FakeSender()

            setContent { CastHandoff(cast, gps, api, player) }
            cast.connect("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession != null }
            runBlocking { cast.say(CastEvent.Position(2_400.0, 7_200.0, isPlaying = true)) }
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession?.positionMs == 2_400_000L }

            cast.disconnect()
            waitUntil(timeoutMillis = UiTimeoutMs) { "play" in player.calls }

            assertTrue(
                "seek:2400000" in player.calls,
                "lost the place the receiver reached: ${player.calls}",
            )
            assertTrue("play" in player.calls, "handed back paused: ${player.calls}")
            assertNull(gps.onCastSeek, "the transport still points at a session that ended")
        }
    }

    @Test
    fun `choosing a receiver stops playback here before it has connected`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            player.currentMs.value = 1_380_000
            val cast = FakeSender()

            setContent { CastHandoff(cast, gps, api, player) }
            waitUntil(timeoutMillis = UiTimeoutMs) { cast.started }

            cast.connecting("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession != null }

            assertTrue("pause" in player.calls, "still playing here while it connects: ${player.calls}")
            assertTrue(cast.loads.isEmpty(), "loaded onto a receiver that is not there yet")
            assertEquals(true, gps.castSession?.connecting)
            assertEquals(1_380_000L, gps.castSession?.positionMs)

            cast.connect("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { cast.loads.isNotEmpty() }
            assertEquals(1_380, cast.loads.single().startPositionSec)
            assertEquals(false, gps.castSession?.connecting)
        }
    }

    @Test
    fun `a title picked while casting goes to the receiver and never plays here`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            val cast = FakeSender()

            setContent { CastHandoff(cast, gps, api, player) }
            cast.connect("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { cast.loads.size == 1 }

            player.calls.clear()
            gps.open(api, player, "movie-2", "sess-2", atSec = 120, lengthSec = 1_400.0)
            // A stream that has not started still reports where the last one stood.
            player.currentMs.value = 1_380_000
            gps.playUnmuted(player)
            waitUntil(timeoutMillis = UiTimeoutMs) { cast.loads.size == 2 }
            waitForIdle()

            assertTrue("play" !in player.calls, "played here as well as on the receiver: ${player.calls}")
            assertTrue("sess-2" in cast.loads.last().contentUrl)
            assertEquals(120, cast.loads.last().startPositionSec, "started where the last title had got to")
            assertEquals(1_400_000L, gps.castSession?.durationMs, "given the last title's length")
            assertTrue("\"duration\":1400.0" in cast.loads.last().payloadJson, cast.loads.last().payloadJson)
            assertEquals("Film movie-2", gps.castSession?.mediaTitle)
        }
    }

    @Test
    fun `failing to connect hands the watch straight back`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            player.currentMs.value = 1_380_000
            val cast = FakeSender()

            setContent { CastHandoff(cast, gps, api, player) }
            cast.connecting("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession != null }
            player.calls.clear()

            cast.disconnect()
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession == null }

            assertTrue("play" in player.calls, "left paused after the receiver never came: ${player.calls}")
        }
    }

    @Test
    fun `casting from the fullscreen player opens the cast player in its place`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            val cast = FakeSender()

            setContent { CastHandoff(cast, gps, api, player) }
            cast.connecting("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession != null }

            assertTrue(gps.isCastExpanded, "left on a mini bar over the screen behind")
            assertTrue(!gps.isFullscreen, "still holding the phone in landscape for a picture on the TV")

            cast.disconnect()
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession == null }
            assertTrue(!gps.isCastExpanded)
        }
    }

    @Test
    fun `play and pause answer the tap before the receiver does`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            val cast = FakeSender()

            setContent { CastHandoff(cast, gps, api, player) }
            cast.connect("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession?.isPlaying == true }

            gps.onCastPlayPause?.invoke()
            assertEquals(false, gps.castSession?.isPlaying)
            waitUntil(timeoutMillis = UiTimeoutMs) { cast.toggles == 1 }

            runBlocking { cast.say(CastEvent.Position(605.0, 7_200.0, isPlaying = false)) }
            waitForIdle()
            assertEquals(false, gps.castSession?.isPlaying)
        }
    }

    @Test
    fun `an episode the receiver moved on to is not handed back as the one it left`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            val cast = FakeSender()

            var resumed: Pair<String, String>? = null
            setContent { CastHandoff(cast, gps, api, player, onResumeHere = { type, id -> resumed = type to id }) }
            cast.connect("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { cast.loads.isNotEmpty() }

            runBlocking { cast.say(CastEvent.EpisodeChanged("ep-2", "Show", "S1 E2 • Next")) }
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession?.subtitle == "S1 E2 • Next" }
            assertEquals("Show", gps.castSession?.mediaTitle)
            runBlocking { cast.say(CastEvent.Position(300.0, 1_400.0, isPlaying = true)) }
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession?.positionMs == 300_000L }
            player.calls.clear()

            cast.disconnect()
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession == null }
            waitForIdle()

            assertTrue("seek:300000" !in player.calls, "put the next episode's place on this one: ${player.calls}")
            assertTrue("play" !in player.calls, "resumed an episode the receiver had already finished: ${player.calls}")
            assertNull(gps.mediaId)
            assertEquals("episode" to "ep-2", resumed)
        }
    }

    /** Measured on the phone: VLC drops a seek made on a stream left paused through a whole cast. */
    @Test
    fun `the place the receiver reached survives a seek the paused player drops`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            player.currentMs.value = 1_000_000
            val cast = FakeSender()

            setContent { CastHandoff(cast, gps, api, player) }
            cast.connect("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession != null }
            runBlocking { cast.say(CastEvent.Position(266.0, 7_200.0, isPlaying = true)) }
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession?.positionMs == 266_000L }
            player.seeksDropped = 1

            cast.disconnect()
            waitUntil(timeoutMillis = UiTimeoutMs) { player.currentMs.value == 266_000L }
            assertTrue(player.isPlaying.value)
        }
    }

    private val adopted = CastEvent.NowPlaying(
        mediaId = "ep-9", mediaType = "episode", title = "Show", subtitle = "S1 E9",
        posterUrl = null, positionSec = 61.0, durationSec = 1_400.0, isPlaying = true,
    )

    @Test
    fun `reopening the app mid-cast picks up what the receiver is playing`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer()
            val gps = GlobalPlayerState()
            val cast = FakeSender()
            var resumed: Pair<String, String>? = null

            setContent { CastHandoff(cast, gps, api, player, onResumeHere = { type, id -> resumed = type to id }) }
            cast.connect("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { "STATUS_REQUEST" in cast.sent }
            runBlocking { cast.say(adopted) }
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession != null }

            val session = assertNotNull(gps.castSession)
            assertEquals("ep-9", session.mediaId)
            assertEquals("S1 E9", session.subtitle)
            assertEquals(61_000L, session.positionMs)
            assertEquals(1_400_000L, session.durationMs)
            assertTrue(cast.loads.isEmpty(), "reloaded a receiver that was already playing")

            gps.onCastSeek?.invoke(120_000L)
            waitUntil(timeoutMillis = UiTimeoutMs) { cast.seeks == listOf(120) }

            cast.disconnect()
            waitUntil(timeoutMillis = UiTimeoutMs) { resumed != null }
            assertEquals("episode" to "ep-9", resumed)
            assertTrue("play" !in player.calls)
        }
    }

    @Test
    fun `a title that played to its end on the receiver is not picked up here`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            val cast = FakeSender()
            var resumed = false

            setContent { CastHandoff(cast, gps, api, player, onResumeHere = { _, _ -> resumed = true }) }
            cast.connect("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession != null }
            player.calls.clear()

            runBlocking { cast.say(CastEvent.Finished) }
            waitUntil(timeoutMillis = UiTimeoutMs) { cast.stopped && gps.castSession == null }
            waitForIdle()

            assertTrue("play" !in player.calls, "resumed a title that had finished: ${player.calls}")
            assertTrue(!resumed)
        }
    }

    @Test
    fun `the stream is decided by what the receiver says it decodes`() = runUiTest {
        val sessions = "/api/v1/admin/playback/sessions"
        StubServer(routes + (sessions to { _: HttpExchange -> """{"session_id":"cast-sess","method":"direct_play","direct_url":"/d"}""" })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            val cast = FakeSender(
                ClientCapabilities(listOf("h264", "hevc"), listOf("aac", "ac3"), listOf("mp4"), maxHeight = 1080, hdrFormats = emptyList()),
            )

            setContent { CastHandoff(cast, gps, api, player) }
            cast.connect("Projector TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { cast.loads.isNotEmpty() }

            val asked = stub.requestBodies.getValue(sessions)
            assertTrue(""""video_codecs":["h264","hevc"]""" in asked, asked)
            assertTrue(""""max_height":1080""" in asked, asked)
            assertTrue(""""hdr_formats":[]""" in asked, asked)
            assertTrue(""""max_height":1080""" in cast.loads.single().payloadJson)
            assertTrue(""""hdr_formats":[]""" in cast.loads.single().payloadJson)
        }
    }

    @Test
    fun `a receiver too old to say what it decodes is judged by its name`() = runUiTest {
        val sessions = "/api/v1/admin/playback/sessions"
        StubServer(routes + (sessions to { _: HttpExchange -> """{"session_id":"cast-sess","method":"direct_play","direct_url":"/d"}""" })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            val cast = FakeSender(decodes = null)

            setContent { CastHandoff(cast, gps, api, player) }
            cast.connect("Lounge Google TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { cast.loads.isNotEmpty() }

            assertTrue(""""eac3"""" in stub.requestBodies.getValue(sessions))
        }
    }

    private val tracks = CastTracks(
        subtitles = listOf(CastTextTrack(id = 1, language = "eng"), CastTextTrack(id = 2, language = "fre")),
        activeSubtitleId = 2,
        audioTracks = listOf(CastAudioTrack(streamIndex = 1, language = "jpn"), CastAudioTrack(streamIndex = 2, language = "eng")),
        activeAudioIndex = 1,
    )

    @Test
    fun `the receiver's tracks and skips can be worked from here`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = loaded(api, player)
            val cast = FakeSender()

            setContent { CastHandoff(cast, gps, api, player) }
            cast.connect("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession != null && "TRACKS_REQUEST" in cast.sent }

            runBlocking { cast.say(CastEvent.TracksChanged(tracks)) }
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession?.tracks != null }
            runBlocking { cast.say(CastEvent.SkipChanged(CastSkip("intro", 90.0))) }
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession?.skip != null }

            gps.onCastSubtitle?.invoke(null)
            gps.onCastAudio?.invoke(2)
            waitUntil(timeoutMillis = UiTimeoutMs) { "AUDIO_SWITCH" in cast.sent }

            assertEquals(
                listOf("SUBTITLE_CHANGE" to """{"trackId":null}""", "AUDIO_SWITCH" to """{"streamIndex":2}"""),
                cast.payloads.filter { it.first in setOf("SUBTITLE_CHANGE", "AUDIO_SWITCH") },
            )
            val shown = assertNotNull(gps.castSession?.tracks)
            assertNull(shown.activeSubtitleId)
            assertEquals(2, shown.activeAudioIndex)

            runBlocking { cast.say(CastEvent.SkipChanged(null)) }
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession?.skip == null }
        }
    }
}
