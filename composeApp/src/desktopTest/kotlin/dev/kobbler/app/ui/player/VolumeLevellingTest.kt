package dev.kobbler.app.ui.player

import androidx.compose.runtime.snapshots.Snapshot
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LibraryEventFrame
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
import kotlin.test.assertEquals

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

    private suspend fun GlobalPlayerState.open(player: FakePlayer, lufs: Double?) = load(
        player = player, api = api,
        type = "episode", id = "ep-1",
        streamUrl = "http://127.0.0.1:1/stream", newSessionId = "s1",
        newTitle = "Show", newSubtitle = null,
        newPosterUrl = null, newLogoUrl = null,
        destination = null,
        seekToSec = 0,
        session = KobblerApi.StreamSession(url = "http://127.0.0.1:1/stream", sessionId = "s1", lufs = lufs),
    )

    @Test
    fun aSessionsLoudnessLevelsThePlayerAndLaterOnesFollow() = levelled { gps, player ->
        gps.open(player, lufs = -20.0)
        player.awaitGain(loudnessGain(-20.0))

        applyDebridSessionFrame(
            gps, api, "s1",
            LibraryEventFrame("loudness", Json.parseToJsonElement("""{"lufs":-9.0}""") as JsonObject),
        )
        player.awaitGain(loudnessGain(-9.0))

        gps.open(player, lufs = null)
        player.awaitGain(1f)
    }

    @Test
    fun turningLevellingOffMidPlayRestoresTheVolumeAndOnLevelsAgain() {
        val previous = normalizeVolume
        try {
            levelled { gps, player ->
                gps.open(player, lufs = -9.0)
                player.awaitGain(loudnessGain(-9.0))
                normalizeVolume = false
                player.awaitGain(1f)
                normalizeVolume = true
                player.awaitGain(loudnessGain(-9.0))
            }
        } finally {
            normalizeVolume = previous
        }
    }
}
