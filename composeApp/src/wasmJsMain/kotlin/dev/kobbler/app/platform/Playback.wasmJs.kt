package dev.kobbler.app.platform

import dev.kobbler.kobplayer.Player as KobPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual object PlaybackLifecycle {
    actual fun startBackgroundPlayback() {}
    actual fun stopBackgroundPlayback() {}
    actual fun setVideoPlaying(playing: Boolean) {}
    actual fun isInPipMode(): Boolean = false

    actual val isInPipModeFlow: StateFlow<Boolean> = MutableStateFlow(false)
    actual fun updateNowPlaying(
        title: String, subtitle: String?, coverUrl: String?,
        isPlaying: Boolean, currentMs: Long, durationMs: Long, isAudioOnly: Boolean,
        videoWidth: Int, videoHeight: Int,
    ) {}
    actual fun setControls(
        onPlayPause: (() -> Unit)?, onSkipForward: (() -> Unit)?,
        onSkipBack: (() -> Unit)?, onStop: (() -> Unit)?,
        timeline: PlaybackTimeline?,
    ) {}
    actual fun setKeepScreenOn(on: Boolean) {}
    actual fun setFullscreenMode(enabled: Boolean) {}
    actual fun setBrightnessLock(level: Float) {}
    actual fun setPlayer(player: KobPlayer?) {}
    actual fun releasePlayer(player: KobPlayer) {}

    actual fun requestAudioFocus() {}
    actual fun abandonAudioFocus() {}
}
