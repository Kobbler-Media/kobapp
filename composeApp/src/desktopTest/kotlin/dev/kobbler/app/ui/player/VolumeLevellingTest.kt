package dev.kobbler.app.ui.player

import androidx.compose.runtime.snapshots.Snapshot
import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LibraryEventFrame
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.kobplayer.Loudness
import dev.kobbler.kobplayer.TrackInfo
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

    private fun track(streamIndex: Int, loudness: Loudness?, isDefault: Boolean = false) = AudioTrackDto(
        streamIndex = streamIndex, isDefault = isDefault,
        lufs = loudness?.lufs, downmixPeakDb = loudness?.downmixPeakDb,
    )

    private suspend fun GlobalPlayerState.open(
        player: FakePlayer,
        tracks: List<AudioTrackDto>,
        selected: Int? = tracks.firstOrNull()?.streamIndex,
    ) = load(
        player = player, api = api,
        type = "episode", id = "ep-1",
        streamUrl = "http://127.0.0.1:1/stream", newSessionId = "s1",
        newTitle = "Show", newSubtitle = null,
        newPosterUrl = null, newLogoUrl = null,
        destination = null,
        seekToSec = 0,
        session = KobblerApi.StreamSession(
            url = "http://127.0.0.1:1/stream", sessionId = "s1",
            audioTracks = tracks, selectedAudioIndex = selected,
        ),
    )

    private val dub = Loudness(-26.8, -5.3)
    private val original = Loudness(-24.7, -4.0)

    @Test
    fun theLevelFollowsTheAudioTrackThatIsPlaying() = levelled { gps, player ->
        gps.open(player, listOf(track(1, dub, isDefault = true), track(2, original)), selected = 2)
        player.awaitGain(loudnessGain(original))

        gps.selectedServerAudioIndex = 1
        player.awaitGain(loudnessGain(dub))

        gps.open(player, emptyList())
        player.awaitGain(1f)
    }

    @Test
    fun aTrackNobodyMeasuredIsLeftFlatRatherThanBorrowingAnothersLevel() = levelled { gps, player ->
        gps.open(player, listOf(track(1, Loudness(-30.0, -10.0), isDefault = true), track(2, null)), selected = 2)
        delay(100)
        player.awaitGain(1f)
    }

    @Test
    fun levelsMeasuredMidPlayReachThePlayingTrack() = levelled { gps, player ->
        gps.open(player, listOf(track(1, null), track(2, null)), selected = 2)
        applyDebridSessionFrame(
            gps, api, "s1",
            LibraryEventFrame(
                "loudness",
                Json.parseToJsonElement(
                    """{"tracks":[{"stream_index":1,"lufs":-26.8,"downmix_peak_db":-5.3},{"stream_index":2,"lufs":-24.7,"downmix_peak_db":-4.0}]}""",
                ) as JsonObject,
            ),
        )
        player.awaitGain(loudnessGain(original))
    }

    @Test
    fun aDebridStreamThatLearnsItsTracksMidPlayLevelsTheOneVlcIsPlaying() = levelled { gps, player ->
        gps.open(player, emptyList())
        player.audioTracks.value = listOf(TrackInfo("audio/1", "Czech"), TrackInfo("audio/2", "English"))
        player.selectedAudioTrack.value = player.audioTracks.value[1]
        applyDebridSessionFrame(
            gps, api, "s1",
            LibraryEventFrame(
                "probe_complete",
                Json.parseToJsonElement(
                    """{"audio_tracks":[{"stream_index":1,"is_default":true,"lufs":-26.8,"downmix_peak_db":-5.3},{"stream_index":2,"lufs":-24.7,"downmix_peak_db":-4.0}]}""",
                ) as JsonObject,
            ),
            player,
        )
        player.awaitGain(loudnessGain(original))
        assertEquals(2, gps.selectedServerAudioIndex)
    }

    @Test
    fun aServerSessionCarriesEachTracksLevelToThePlayer() = levelled { gps, player ->
        val sessionJson = """{"session_id":"s1","method":"direct_play","direct_url":"/stream","selected_audio_index":2,"audio_tracks":[""" +
            """{"stream_index":1,"is_default":true,"lufs":-26.8,"downmix_peak_db":-5.3},{"stream_index":2,"lufs":-24.7,"downmix_peak_db":-4.0}]}"""
        StubServer(mapOf("/api/v1/admin/playback/sessions" to { _: HttpExchange -> sessionJson })).use { stub ->
            val session = KobblerApi(HttpClient(), stub.url, "token").createDownloadSession("movie", "m1")
            assertEquals(listOf(dub, original), session?.audioTracks?.map { it.loudness })
            gps.load(
                player = player, api = api, type = "movie", id = "m1",
                streamUrl = "http://127.0.0.1:1/stream", newSessionId = "s1",
                newTitle = "Film", newSubtitle = null, newPosterUrl = null, newLogoUrl = null,
                destination = null, seekToSec = 0, session = session,
            )
            player.awaitGain(loudnessGain(original))
        }
    }

    @Test
    fun switchingTracksThroughTheServerLevelsTheNewTrack() = levelled { gps, player ->
        val switchPath = "/api/v1/admin/playback/sessions/s1/switch-audio"
        val switched = """{"session_id":"s2","method":"audio_transcode","playlist_url":"/hls/s2.m3u8","selected_audio_index":1,"audio_tracks":[""" +
            """{"stream_index":1,"lufs":-26.8,"downmix_peak_db":-5.3},{"stream_index":2,"lufs":-24.7,"downmix_peak_db":-4.0}]}"""
        StubServer(mapOf(switchPath to { _: HttpExchange -> switched })).use { stub ->
            val server = KobblerApi(HttpClient(), stub.url, "token")
            gps.open(player, listOf(track(1, null), track(2, null)), selected = 2)
            player.awaitGain(1f)
            switchAudioAndResume(server, player, gps, "s1", 1)
            player.awaitGain(loudnessGain(dub))
        }
    }

    @Test
    fun turningLevellingOffMidPlayRestoresTheVolumeAndOnLevelsAgain() {
        val previous = normalizeVolume
        try {
            levelled { gps, player ->
                gps.open(player, listOf(track(1, Loudness(-9.0))))
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
