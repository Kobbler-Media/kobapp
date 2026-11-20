package dev.kobbler.app.ui.player

import androidx.compose.ui.test.ExperimentalTestApi
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.platform.CastFeature
import dev.kobbler.app.runUiTest
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Kobbler receiver saves the position itself, and a second writer with a staler
 * position would undo it. A receiver that cannot — a DLNA renderer — has only this
 * device to save it.
 */
@OptIn(ExperimentalTestApi::class)
class CastProgressTest {

    private val saved = "/api/v1/admin/movies/movie-1/watch-state/position"

    private val routes = mapOf(
        "/api/v1/admin/playback/tokens" to { _: HttpExchange -> """{"token":"cast-tok"}""" },
        "/api/v1/library/stats" to { _: HttpExchange -> """{"kobload_configured":false}""" },
        "/api/v1/admin/config" to { _: HttpExchange -> "{}" },
        saved to { _: HttpExchange -> "{}" },
    )

    private fun casting(features: Set<CastFeature>, test: suspend (StubServer, FakeSender, GlobalPlayerState) -> Unit) = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 7_200_000 }
            val gps = GlobalPlayerState()
            runBlocking {
                val url = "${stub.url}/api/v1/admin/playback/sessions/sess-1/direct"
                gps.load(
                    player = player, api = api, type = "movie", id = "movie-1",
                    streamUrl = url, newSessionId = "sess-1", newTitle = "Film", newSubtitle = null,
                    newPosterUrl = null, newLogoUrl = null, destination = null, seekToSec = 0,
                    session = KobblerApi.StreamSession(url = url, sessionId = "sess-1", method = "direct_play", durationSeconds = 7200.0),
                )
            }
            val cast = FakeSender(features = features)
            setContent { CastHandoff(cast, gps, api, player) }
            cast.connect("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession != null }
            runBlocking { cast.say(CastEvent.Position(600.0, 7_200.0, isPlaying = true)) }
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.castSession?.positionMs == 600_000L }
            runBlocking { test(stub, cast, gps) }
        }
    }

    @Test
    fun `a receiver that saves for itself is left to it`() = casting(CastFeature.entries.toSet()) { stub, cast, gps ->
        cast.say(CastEvent.Position(700.0, 7_200.0, isPlaying = true))
        while (gps.castSession?.positionMs != 700_000L) kotlinx.coroutines.delay(20)
        assertEquals(null, stub.requestBodies[saved])
    }

    @Test
    fun `a receiver that cannot save has this device save for it`() =
        casting(CastFeature.entries.toSet() - CastFeature.ReceiverReportsProgress) { stub, _, _ ->
            val deadline = System.currentTimeMillis() + UiTimeoutMs
            while (stub.requestBodies[saved] == null && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(20)
            assertEquals(600, Regex("\"position_seconds\":(\\d+)").find(stub.requestBodies[saved].orEmpty())?.groupValues?.get(1)?.toInt())
        }
}
