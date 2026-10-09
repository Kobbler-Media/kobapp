package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LiveChannel
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A channel change leaves the outgoing channel alone until there is something to cut to.
 *
 * The old tune outlives the request now — the server takes the new channel's tuner before
 * it lets the old one go — so what is on screen while the switch is in flight is a live
 * picture, not the tail of a stream that has stopped. Blanking it, freezing it or putting
 * a caption over it are all ways of covering a gap this no longer has.
 */
class LiveChannelSwitchTest {

    // Nothing listens here, so the switch request fails at once — which is the point:
    // whatever the player was told before the request is all that can have happened.
    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    private fun channel(id: String) = LiveChannel(
        id = id, name = "Channel $id", lcn = null, callsign = null, logoUrl = null,
        network = null, region = null, kind = null, hd = false, enabled = true,
        favourite = false, muxLabel = null, typeLabel = null, hasGuide = true,
        guideEntryCount = 0, videoCodec = null, audioCodec = null, signalQuality = null,
    )

    private fun tuned() = GlobalPlayerState().apply {
        mediaType = "livetv"
        mediaId = "chan-a"
        liveSessionId = "live-1"
        isLive = true
        isFullscreen = true
    }

    private fun playing() = FakePlayer().apply { isPlaying.value = true }

    @Test
    fun `the channel being left keeps playing until the new one is open`() = runTest {
        val player = playing()
        switchLiveChannel(channel("chan-b"), player, tuned(), api)

        assertTrue(
            player.calls.none { it == "pause" || it == "stop" },
            "the picture was interrupted before there was anything to replace it: ${player.calls}",
        )
        assertTrue(
            player.calls.none { it == "load" },
            "the request failed, so nothing should have been opened: ${player.calls}",
        )
    }

    /** Picking what is already on is not a channel change, and must not blank the picture. */
    @Test
    fun `picking the channel already playing leaves it alone`() = runTest {
        val player = playing()
        switchLiveChannel(channel("chan-a"), player, tuned(), api)

        assertEquals(emptyList(), player.calls)
    }

    @Test
    fun `a switch that fails gives the channel back`() = runTest {
        val gps = tuned()
        switchLiveChannel(channel("chan-b"), playing(), gps, api)

        assertEquals("chan-a", gps.mediaId, "a failed switch left the player on a channel it never opened")
        // The picture carries the change on its own. A caption over it is chrome for a
        // gesture that is meant to read as one cut.
        assertNull(gps.liveSeekingLabel, "a channel change captioned itself")
    }
}
