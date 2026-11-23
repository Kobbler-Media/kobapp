package dev.kobbler.app.ui.player

import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Whether the viewer has playback paused, kept across the stream being reopened under them.
 *
 * A reconnect, a re-resolve or a switch of release rebuilds the player, and by the time the
 * failure that prompted it arrives the player no longer says it was paused. The answer is
 * read off the player's own state as it goes, so it holds whatever paused it: the
 * transport, a media key, a headset unplugged, a cast taking over.
 */
internal class ViewerPause {
    var paused = false
        private set
    private var reopening = false
    private var playedSinceOpen = false
    private var followed: Player? = null
    private var follow: Job? = null

    fun follow(player: Player, scope: CoroutineScope, preloading: () -> Boolean) {
        if (followed === player) return
        followed = player
        follow?.cancel()
        follow = scope.launch {
            player.state.collect { state ->
                // A load pauses the player itself to buffer silently; that is not the viewer's.
                if (preloading()) return@collect
                when (state) {
                    // This collector can run after the load has already asked to play, so
                    // the preload check alone reads the load's own hold as the viewer's.
                    PlayerState.PAUSED -> if (playedSinceOpen) paused = true
                    PlayerState.PLAYING -> {
                        playedSinceOpen = true
                        paused = false
                    }
                    else -> {}
                }
            }
        }
    }

    fun streamOpening() {
        playedSinceOpen = false
    }

    fun reopening() {
        reopening = true
    }

    fun settle() {
        reopening = false
    }

    fun forget() {
        paused = false
        reopening = false
    }

    /** Whether the start about to happen is a reopen the viewer had paused, and so stays paused. */
    fun holds(): Boolean = (reopening && paused).also { reopening = false }
}
