package dev.kobbler.app.ui.player

import androidx.compose.runtime.snapshots.Snapshot
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LibraryEventFrame
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.kobplayer.Loudness
import dev.kobbler.kobplayer.loudnessGain
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VolumeLevellingTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    private fun levelled(block: suspend (GlobalPlayerState, FakePlayer) -> Unit) = runBlocking {
        val gps = GlobalPlayerState()
        val player = FakePlayer()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { gps.levelLoudness(player) }
        try {
            block(gps, player)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun FakePlayer.awaitGain(gain: Float) = withTimeout(5_000) {
        while (normalization != gain) {
            Snapshot.sendApplyNotifications()
            delay(10)
        }
    }

    private suspend fun GlobalPlayerState.open(player: FakePlayer, loudness: Loudness?) = load(
        player = player, api = api,
        type = "episode", id = "ep-1",
        streamUrl = "http://127.0.0.1:1/stream", newSessionId = "s1",
        newTitle = "Show", newSubtitle = null,
        newPosterUrl = null, newLogoUrl = null,
        destination = null,
        seekToSec = 0,
        session = KobblerApi.StreamSession(
            url = "http://127.0.0.1:1/stream", sessionId = "s1",
            lufs = loudness?.lufs, downmixPeakDb = loudness?.downmixPeakDb,
        ),
    )

    @Test
    fun aSessionsLoudnessLevelsThePlayerAndLaterOnesFollow() = levelled { gps, player ->
        val quietSurround = Loudness(-23.04, -1.6)
        gps.open(player, quietSurround)
        player.awaitGain(loudnessGain(quietSurround))
        assertTrue(abs(player.normalization - 1.0715f) < 0.001, "capped at the downmix peak: ${player.normalization}")

        applyDebridSessionFrame(
            gps, api, "s1",
            LibraryEventFrame("loudness", Json.parseToJsonElement("""{"lufs":-30.0,"downmix_peak_db":-6.0}""") as JsonObject),
        )
        player.awaitGain(loudnessGain(Loudness(-30.0, -6.0)))
        assertTrue(abs(player.normalization - 1.778f) < 0.001, "capped at the downmix peak: ${player.normalization}")

        gps.open(player, null)
        player.awaitGain(1f)
    }

    @Test
    fun aServerSessionCarriesItsDownmixPeakToThePlayer() = levelled { gps, player ->
        val sessionJson = """{"session_id":"s1","method":"direct_play","direct_url":"/stream","lufs":-23.04,"downmix_peak_db":-1.6}"""
        StubServer(mapOf("/api/v1/admin/playback/sessions" to { _: HttpExchange -> sessionJson })).use { stub ->
            val session = KobblerApi(HttpClient(), stub.url, "token").createDownloadSession("movie", "m1")
            assertEquals(Loudness(-23.04, -1.6), session?.loudness)
            gps.open(player, session?.loudness)
            player.awaitGain(loudnessGain(Loudness(-23.04, -1.6)))
        }
    }

    @Test
    fun turningLevellingOffMidPlayRestoresTheVolumeAndOnLevelsAgain() {
        val previous = normalizeVolume
        try {
            levelled { gps, player ->
                gps.open(player, Loudness(-9.0))
                player.awaitGain(loudnessGain(Loudness(-9.0)))
                normalizeVolume = false
                player.awaitGain(1f)
                normalizeVolume = true
                player.awaitGain(loudnessGain(Loudness(-9.0)))
            }
        } finally {
            normalizeVolume = previous
        }
    }
}
