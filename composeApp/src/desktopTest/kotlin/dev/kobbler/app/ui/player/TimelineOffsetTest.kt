package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SWITCH_AUDIO = "/api/v1/admin/playback/sessions/sess-1/switch-audio"

/** The switch restarts the transcode at `resume_time`, so the playlist comes back rebased. */
private const val REBASED_SWITCH = """
{"session_id":"sess-1","method":"VideoTranscode",
 "playlist_url":"/api/v1/admin/playback/sessions/sess-1/master.m3u8",
 "duration_seconds":3600,"subtitles":[],"audio_tracks":[],"start_time":1200}
"""

/** The server declined the resume (too close to zero) and served the whole title. */
private const val PLAIN_SWITCH = """
{"session_id":"sess-1","method":"VideoTranscode",
 "playlist_url":"/api/v1/admin/playback/sessions/sess-1/master.m3u8",
 "duration_seconds":3600,"subtitles":[],"audio_tracks":[]}
"""

class TimelineOffsetTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    private fun loadedState(serverStartSeconds: Int?): GlobalPlayerState {
        val gps = GlobalPlayerState()
        runBlocking {
            gps.load(
                player = FakePlayer(), api = api,
                type = "episode", id = "ep-1",
                streamUrl = "http://127.0.0.1:1/stream", newSessionId = "sess-1",
                newTitle = "Show", newSubtitle = null,
                newPosterUrl = null, newLogoUrl = null,
                destination = null,
                session = KobblerApi.StreamSession(
                    url = "http://127.0.0.1:1/stream",
                    sessionId = "sess-1",
                    serverStartSeconds = serverStartSeconds,
                ),
            )
        }
        return gps
    }

    @Test
    fun aDirectPlaySessionLeavesTheTimelineAlone() {
        assertEquals(PlayerTimeline.Direct, loadedState(null).timeline)
    }

    @Test
    fun aRebasedSessionCarriesItsOffsetIntoTheState() {
        assertEquals(1_200_000L, loadedState(1200).timeline.offsetMs)
    }

    @Test
    fun closingThePlayerClearsTheOffset() {
        val gps = loadedState(1200)
        val player = FakePlayer()
        gps.stop(player, api, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        assertEquals(PlayerTimeline.Direct, gps.timeline)
    }

    @Test
    fun switchingAudioCarriesTheServersRebase() {
        StubServer(mapOf(SWITCH_AUDIO to { REBASED_SWITCH })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val session = runBlocking { api.switchAudioTrack("sess-1", 2, 1200, "file-1") }
            assertEquals(1200, session?.serverStartSeconds)
        }
    }

    @Test
    fun switchingAudioOnARebasedSessionDoesNotJumpTwice() {
        StubServer(mapOf(SWITCH_AUDIO to { REBASED_SWITCH })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 2_400_000; it.currentMs.value = 1_200_000 }
            val gps = GlobalPlayerState().also { it.sessionId = "sess-1" }

            runBlocking { switchAudioAndResume(api, player, gps, "sess-1", 2) }

            assertEquals(
                """{"audio_stream_index":2,"resume_time":1200}""",
                stub.requestBodies[SWITCH_AUDIO],
                "the server was told a position in the player's clock, not the title's",
            )
            assertEquals(1_200_000L, gps.timeline.offsetMs)
            assertNull(
                player.loadedSource?.startTimeSec,
                "asked for an offset the rebased playlist had already applied: ${player.calls}",
            )
        }
    }

    @Test
    fun switchingAudioWithoutARebaseStillPutsThePlayheadBack() {
        StubServer(mapOf(SWITCH_AUDIO to { PLAIN_SWITCH })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 3_600_000; it.currentMs.value = 1_200_000 }
            val gps = GlobalPlayerState().also { it.sessionId = "sess-1" }

            runBlocking { switchAudioAndResume(api, player, gps, "sess-1", 2) }

            assertEquals(PlayerTimeline.Direct, gps.timeline)
            assertEquals(1200, player.loadedSource?.startTimeSec, "lost the playhead: ${player.calls}")
        }
    }
}
