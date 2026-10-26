package dev.kobbler.kobplayer

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.os.Build
import androidx.media3.common.Player
import androidx.media3.session.MediaSession

fun playPauseSession(context: Context, player: Player, id: String = ""): MediaSession =
    MediaSession.Builder(context, player).setId(id).setCallback(PlayPauseKeys).build()

private val playPauseKeyCodes = setOf(
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_HEADSETHOOK,
    KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PAUSE,
)

// Every press is a press: Media3 holds a play/pause key back to see whether a second makes it
// a double tap, which it turns into "next". These sessions have no next, so the pair did
// nothing at all, and every single press waited out the double-tap timeout first.
private object PlayPauseKeys : MediaSession.Callback {
    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent,
    ): Boolean {
        val key = intent.keyEvent() ?: return false
        if (key.keyCode !in playPauseKeyCodes) return false
        if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount != 0) return true
        val player = session.player
        when (key.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
            else -> if (player.playWhenReady) player.pause() else player.play()
        }
        return true
    }
}

private fun Intent.keyEvent(): KeyEvent? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_KEY_EVENT)
