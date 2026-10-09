package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Field-coded is a fact about the tuner's own stream, and nothing else.
 *
 * The buffer behind a live channel is the server's transcode — deinterlaced, and 720p at
 * that — so a rewind or a Restart plays a different stream from the tune that opened the
 * session. Carrying the tune's answer onto it put the one source that never needed a
 * software decoder onto one anyway.
 */
class FieldCodedTimeshiftTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")
    private val playlist = "http://127.0.0.1:1/live/sessions/s-1/stream.m3u8"

    private suspend fun tunedToFieldCodedBroadcast(player: FakePlayer): GlobalPlayerState {
        val gps = GlobalPlayerState()
        gps.liveFieldCoded = true
        gps.load(
            player = player, api = api,
            type = "livetv", id = "chan-1",
            streamUrl = "http://127.0.0.1:1/live/chan-1/stream.ts", newSessionId = null,
            newTitle = "Channel One", newSubtitle = null,
            newPosterUrl = null, newLogoUrl = null,
            destination = null,
        )
        gps.livePlaylistUrl = playlist
        return gps
    }

    @Test
    fun `the broadcast itself is opened as field-coded`() = runTest {
        val player = FakePlayer()
        tunedToFieldCodedBroadcast(player)

        assertEquals(true, player.loadedSource?.fieldCoded)
    }

    @Test
    fun `the buffer behind it is not`() = runTest {
        val player = FakePlayer()
        val gps = tunedToFieldCodedBroadcast(player)

        enterLiveTimeshift(gps, player, api, seekToSec = 0)

        assertEquals(
            playlist, player.loadedUrl,
            "the rewind never swapped onto the buffer, so this proves nothing",
        )
        assertEquals(
            false, player.loadedSource?.fieldCoded,
            "the buffer is the server's deinterlaced transcode and was opened as field-coded anyway",
        )
        assertEquals(false, gps.liveFieldCoded, "the tune's answer outlived the tune")
    }
}
