package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Stepping back into the buffer swaps the source under the same session, and everything
 * the tune knew about its own stream stops being true at that moment: the buffer is the
 * server's transcode, with its own geometry and its own scrubber stills.
 */
class LiveTimeshiftSourceTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")
    private val playlist = "http://127.0.0.1:1/live/sessions/s-1/stream.m3u8"

    private suspend fun tuned(player: FakePlayer): GlobalPlayerState {
        val gps = GlobalPlayerState()
        gps.load(
            player = player, api = api,
            type = "livetv", id = "chan-1",
            streamUrl = "http://127.0.0.1:1/live/chan-1/stream.ts", newSessionId = null,
            newTitle = "Channel One", newSubtitle = null,
            newPosterUrl = null, newLogoUrl = null,
            destination = null,
        )
        // What applyLiveSession puts back after load(): the tuner's own stream.
        gps.videoWidth = 1440
        gps.videoHeight = 1080
        gps.thumbnailsUrl = "http://127.0.0.1:1/live/sessions/s-1/thumbs.vtt"
        gps.livePlaylistUrl = playlist
        return gps
    }

    /**
     * The stills are generated off the same feed the buffer is filled from, so they are
     * exactly what a rewind needs — and [GlobalPlayerState.load] clears them, because a
     * live tune hands it no session payload to read them back out of.
     */
    @Test
    fun `the buffer keeps the scrubber's stills`() = runTest {
        val player = FakePlayer()
        val gps = tuned(player)
        val stills = gps.thumbnailsUrl

        enterLiveTimeshift(gps, player, api, seekToSec = 60)

        assertEquals(playlist, player.loadedUrl, "the rewind never swapped source; this proves nothing")
        assertEquals(stills, gps.thumbnailsUrl, "the rewind threw away the scrubber's stills")
    }

    /**
     * The tuner's dimensions describe the broadcast, which is anamorphic as often as not.
     * Carrying them onto the transcode measures one stream with another's ruler, and it
     * is the aspect-ratio memory that reads them.
     */
    @Test
    fun `the buffer is not measured with the tuner's dimensions`() = runTest {
        val player = FakePlayer()
        val gps = tuned(player)

        enterLiveTimeshift(gps, player, api, seekToSec = 60)

        assertNull(gps.videoWidth, "the broadcast's width outlived the broadcast")
        assertNull(gps.videoHeight, "the broadcast's height outlived the broadcast")
    }

    /**
     * The last segment of a live playlist is the one still being written. Seeking to the
     * duration the player reports lands in it, which starves the decoder — the picture
     * stops on a frame and never comes back.
     */
    @Test
    fun `the live edge stops short of the end of the playlist`() {
        assertEquals(1_796_000L, liveEdgeSeekMs(1_800_000L))
        assertEquals(0L, liveEdgeSeekMs(1_000L), "a playlist shorter than the slack seeks to its head")
        assertEquals(0L, liveEdgeSeekMs(0L))
    }
}
