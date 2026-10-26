package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.kobplayer.PlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The on-screen button, the mini player and the media session (headset, Bluetooth, lock
 * screen, notification) share one play/pause, and it reads the player's state the same way
 * the session does: a buffering stall is playback, so the press means pause.
 */
class PlayPauseRouteTest {

    private class Case(val name: String, val mediaType: String, val state: PlayerState, val playing: Boolean, val expected: String)

    private val cases = listOf(
        Case("live tv playing", "livetv", PlayerState.PLAYING, playing = true, expected = "pause"),
        Case("live tv buffering", "livetv", PlayerState.BUFFERING, playing = false, expected = "pause"),
        Case("live tv paused", "livetv", PlayerState.PAUSED, playing = false, expected = "play"),
        Case("episode playing", "episode", PlayerState.PLAYING, playing = true, expected = "pause"),
        Case("episode paused", "episode", PlayerState.PAUSED, playing = false, expected = "play"),
    )

    @Test
    fun `one play-pause reads every kind of title by its player state`() {
        val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")
        val failures = cases.mapNotNull { case ->
            val player = FakePlayer().apply {
                state.value = case.state
                isPlaying.value = case.playing
            }
            val gps = GlobalPlayerState().apply {
                mediaType = case.mediaType
                liveTimeshift = true
            }
            gps.togglePlayback(player, api, CoroutineScope(Dispatchers.Unconfined))
            "${case.name}: ${player.calls}".takeUnless { player.calls == listOf(case.expected) }
        }
        assertEquals(emptyList(), failures)
    }
}
