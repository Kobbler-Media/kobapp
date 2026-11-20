package dev.kobbler.app

import android.os.Looper
import androidx.media3.common.Player
import dev.kobbler.app.platform.PlaybackBridge
import dev.kobbler.app.platform.PlaybackTimeline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The media session for what has controls but no player here: a read-aloud, or a cast the app drives itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BridgeMediaPlayerTest {
    private val calls = mutableListOf<String>()
    private var positionMs = 320_000L

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After
    fun clear() = PlaybackBridge.clearNowPlaying()

    @Test
    fun `a cast's place shows on the session and its transport reaches the cast`() {
        PlaybackBridge.setControls(
            onPlayPause = { calls += "toggle" },
            onSkipForward = { calls += "forward" },
            onSkipBack = { calls += "back" },
            onStop = { calls += "stop" },
            timeline = PlaybackTimeline({ positionMs }, { 1_800_000L }, { calls += "seek $it" }, 10_000L, 10_000L),
        )
        PlaybackBridge.updateNowPlaying("Show", "S1 E1", null, isPlaying = true, isAudioOnly = false)
        val player = BridgeMediaPlayer()
        idle()

        assertEquals(320_000L, player.currentPosition)
        assertEquals(1_800_000L, player.duration)
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_BACK))

        player.seekTo(900_000L)
        idle()
        positionMs = 900_000L
        player.seekBack()
        idle()
        player.pause()
        idle()
        player.stop()
        idle()

        assertEquals(listOf("seek 900000", "seek 890000", "toggle", "stop"), calls)
        player.cleanup()
    }

    @Test
    fun `a read-aloud has no timeline, so the session offers no seeking`() {
        PlaybackBridge.setControls({ calls += "toggle" }, { calls += "forward" }, null, { calls += "stop" })
        PlaybackBridge.updateNowPlaying("Book", "Chapter 3", null, isPlaying = true, isAudioOnly = true)
        val player = BridgeMediaPlayer()
        idle()

        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        player.pause()
        idle()
        assertEquals(listOf("toggle"), calls)
        player.cleanup()
    }
}
