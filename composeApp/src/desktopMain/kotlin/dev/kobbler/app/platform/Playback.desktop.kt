package dev.kobbler.app.platform

import dev.kobbler.app.platform.linux.SessionBus
import dev.kobbler.kobplayer.Player as KobPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual object PlaybackLifecycle {
    actual fun startBackgroundPlayback() {} // Desktop doesn't need a service
    actual fun stopBackgroundPlayback() {}
    actual fun setVideoPlaying(playing: Boolean) {} // No PiP on desktop
    actual fun isInPipMode(): Boolean = false

    actual val isInPipModeFlow: StateFlow<Boolean> = MutableStateFlow(false)
    actual fun updateNowPlaying(
        title: String, subtitle: String?, coverUrl: String?,
        isPlaying: Boolean, currentMs: Long, durationMs: Long, isAudioOnly: Boolean,
        videoWidth: Int, videoHeight: Int,
    ) {
        SessionBus.mpris?.update(title, subtitle, coverUrl, isPlaying, currentMs, durationMs)
    }
    actual fun setControls(
        onPlayPause: (() -> Unit)?, onSkipForward: (() -> Unit)?,
        onSkipBack: (() -> Unit)?, onStop: (() -> Unit)?,
        timeline: PlaybackTimeline?,
    ) {
        val mpris = SessionBus.mpris ?: return
        mpris.setControls(onPlayPause, onSkipForward, onSkipBack, onStop, timeline?.seekTo)
        // Every player clears its controls on the way out; that is the end of now-playing.
        if (onPlayPause == null && onStop == null) mpris.clear()
    }
    actual fun setKeepScreenOn(on: Boolean) {
        SessionBus.screenInhibitor?.hold(on)
    }
    actual fun setFullscreenMode(enabled: Boolean) {}
    actual fun setBrightnessLock(level: Float) {}
    private var registered: KobPlayer? = null

    actual fun setPlayer(player: KobPlayer?) {
        registered = player
    }

    // The video player never clears its controls itself; releasing it is the end of now-playing.
    actual fun releasePlayer(player: KobPlayer) {
        if (registered !== player) return
        registered = null
        setControls(null, null, null, null, null)
    }

    actual fun requestAudioFocus() {}
    actual fun abandonAudioFocus() {}
}
