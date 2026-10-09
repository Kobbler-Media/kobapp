package dev.kobbler.app.platform

import dev.kobbler.kobplayer.Player as KobPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual object PlaybackLifecycle {
    actual fun startBackgroundPlayback() {
        // iOS handles background audio via AVAudioSession category
        // This would need to be configured in the iOS app's Info.plist
        // with UIBackgroundModes = audio
    }
    actual fun stopBackgroundPlayback() {}
    actual fun setVideoPlaying(playing: Boolean) {
        // iOS PiP is handled by AVPlayerLayer natively
    }
    actual fun isInPipMode(): Boolean = false

    actual val isInPipModeFlow: StateFlow<Boolean> = MutableStateFlow(false)
    actual fun updateNowPlaying(
        title: String, subtitle: String?, coverUrl: String?,
        isPlaying: Boolean, currentMs: Long, durationMs: Long, isAudioOnly: Boolean,
        videoWidth: Int, videoHeight: Int,
    ) {
        // iOS: would use MPNowPlayingInfoCenter
    }
    actual fun setControls(
        onPlayPause: (() -> Unit)?, onSkipForward: (() -> Unit)?,
        onSkipBack: (() -> Unit)?, onStop: (() -> Unit)?,
        onSeekTo: ((Long) -> Unit)?,
    ) {
        // iOS: would use MPRemoteCommandCenter
    }
    actual fun setKeepScreenOn(on: Boolean) {
        // iOS: UIApplication.shared.isIdleTimerDisabled = on
    }
    actual fun setFullscreenMode(enabled: Boolean) {}
    actual fun setBrightnessLock(level: Float) {
        // iOS: UIScreen.mainScreen.brightness = level (would require @OptIn for native API)
        // Skipped — iOS doesn't suffer from adaptive brightness during playback the
        // same way; the system pauses auto-dim while video is playing fullscreen.
    }
    actual fun setPlayer(player: KobPlayer?) {}
    actual fun releasePlayer(player: KobPlayer) {}

    actual fun requestAudioFocus() {}
    actual fun abandonAudioFocus() {}
}
