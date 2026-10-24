package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.ExperimentalTestApi
import com.sun.net.httpserver.HttpExchange
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

/** A receiver that records what it was told, standing in for one across the room. */
private class FakeSender : CastSender {
    private val _device = MutableStateFlow<CastDevice?>(null)
    override val device: StateFlow<CastDevice?> = _device.asStateFlow()
    private val _events = MutableSharedFlow<CastEvent>(replay = 1, extraBufferCapacity = 8)
    override val events: SharedFlow<CastEvent> = _events.asSharedFlow()

    var started = false
    val loads = mutableListOf<CastLoad>()
    val seeks = mutableListOf<Int>()
    var toggles = 0
    var stopped = false

    override fun start() { started = true }
    override suspend fun load(load: CastLoad) { loads += load }
    override suspend fun send(type: String, payloadJson: String) {}
    override suspend fun seekTo(positionSeconds: Int) { seeks += positionSeconds }
    override suspend fun togglePlayPause() { toggles++ }
    override suspend fun stop() { stopped = true; _device.value = null }

    fun connect(name: String) { _device.value = CastDevice(name) }
    fun disconnect() { _device.value = null }
    suspend fun say(event: CastEvent) { _events.emit(event) }
}

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

    private fun loaded(api: KobblerApi, player: FakePlayer): GlobalPlayerState {
        val gps = GlobalPlayerState()
        runBlocking {
            gps.load(
                player = player, api = api,
                type = "movie", id = "movie-1",
                streamUrl = "http://host/api/v1/admin/playback/sessions/sess-1/stream",
                newSessionId = "sess-1",
                newTitle = "Film", newSubtitle = null,
                newPosterUrl = null, newLogoUrl = null,
                destination = null,
                session = KobblerApi.StreamSession(
                    url = "http://host/api/v1/admin/playback/sessions/sess-1/stream",
                    sessionId = "sess-1",
                    method = "direct_play",
                ),
            )
        }
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
}
