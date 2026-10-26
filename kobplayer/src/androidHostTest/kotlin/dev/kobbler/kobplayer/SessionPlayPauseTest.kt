package dev.kobbler.kobplayer

import android.os.Looper
import android.view.KeyEvent
import androidx.media3.session.MediaSession
import androidx.media3.session.dispatchLegacyMediaButton
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A headset, Bluetooth, the lock screen and the notification all reach the player through
 * the media session, and must take the same play/pause route as the on-screen button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionPlayPauseTest {

    private class Case(
        val name: String,
        val initial: PlayerState,
        val muted: Boolean = false,
        val toggles: Int,
        val send: (MediaSession) -> Unit,
    )

    private fun keys(vararg codes: Int): (MediaSession) -> Unit = { session ->
        codes.forEach { code ->
            session.dispatchLegacyMediaButton(KeyEvent(KeyEvent.ACTION_DOWN, code))
            session.dispatchLegacyMediaButton(KeyEvent(KeyEvent.ACTION_UP, code))
            idle()
        }
    }

    private val cases = listOf(
        Case("headset play/pause while playing", PlayerState.PLAYING, toggles = 1, send = keys(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)),
        Case("headset hook while paused", PlayerState.PAUSED, toggles = 1, send = keys(KeyEvent.KEYCODE_HEADSETHOOK)),
        Case("bluetooth pause while playing", PlayerState.PLAYING, toggles = 1, send = keys(KeyEvent.KEYCODE_MEDIA_PAUSE)),
        Case("bluetooth pause while buffering", PlayerState.BUFFERING, toggles = 1, send = keys(KeyEvent.KEYCODE_MEDIA_PAUSE)),
        Case("bluetooth play while paused", PlayerState.PAUSED, toggles = 1, send = keys(KeyEvent.KEYCODE_MEDIA_PLAY)),
        Case("bluetooth play while playing", PlayerState.PLAYING, toggles = 0, send = keys(KeyEvent.KEYCODE_MEDIA_PLAY)),
        Case("bluetooth pause while paused", PlayerState.PAUSED, toggles = 0, send = keys(KeyEvent.KEYCODE_MEDIA_PAUSE)),
        Case("double tap with no next item", PlayerState.PLAYING, toggles = 2, send = keys(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)),
        Case("lock screen pause", PlayerState.PLAYING, toggles = 1, send = { it.player.pause(); idle() }),
        Case("lock screen play", PlayerState.PAUSED, toggles = 1, send = { it.player.play(); idle() }),
        Case("play while a preload is muted", PlayerState.PAUSED, muted = true, toggles = 0, send = keys(KeyEvent.KEYCODE_MEDIA_PLAY)),
    )

    @Test
    fun `every session entry point takes the player's own play-pause route`() {
        val failures = cases.mapNotNull { case ->
            val stub = StubPlayer(case.initial, case.muted)
            var toggles = 0
            val adapter = Media3PlayerAdapter(stub.player, togglePlayPause = {
                toggles++
                stub.state.value = if (playPauseIntent(stub.state.value) == PlayPauseIntent.Play) PlayerState.PLAYING else PlayerState.PAUSED
            })
            val session = playPauseSession(RuntimeEnvironment.getApplication(), adapter, id = case.name)
            idle()
            val crash = runCatching { case.send(session); idle(Duration.ofSeconds(1)) }.exceptionOrNull()
            session.release()
            adapter.cleanup()
            when {
                crash != null -> "${case.name}: ${crash::class.simpleName} ${crash.message}"
                stub.direct.isNotEmpty() -> "${case.name}: bypassed the route with ${stub.direct}"
                toggles != case.toggles -> "${case.name}: toggled $toggles times, expected ${case.toggles}"
                else -> null
            }
        }
        assertEquals(emptyList(), failures)
    }

    private fun idle(by: Duration = Duration.ZERO) {
        shadowOf(Looper.getMainLooper()).idleFor(by)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
