package dev.kobbler.app.platform

import dev.kobbler.kobplayer.Player as KobPlayer
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-specific playback lifecycle hooks.
 * On Android: starts/stops a foreground service for background playback
 * and manages media notification with controls.
 * On other platforms: no-op.
 */
expect object PlaybackLifecycle {
    /** Start background playback support (foreground service on Android). */
    fun startBackgroundPlayback()
    /** Stop background playback support. */
    fun stopBackgroundPlayback()
    /** Notify that video is actively playing (enables auto-PiP on Android). */
    fun setVideoPlaying(playing: Boolean)
    /** Whether the app is currently in PiP mode (hide controls). */
    fun isInPipMode(): Boolean

    /**
     * [isInPipMode] as a flow, so the player can observe it instead of polling. Only
     * Android ever emits a second value.
     */
    val isInPipModeFlow: StateFlow<Boolean>

    /** Update the media notification with current playback info. */
    fun updateNowPlaying(
        title: String,
        subtitle: String?,
        coverUrl: String?,
        isPlaying: Boolean,
        currentMs: Long,
        durationMs: Long,
        isAudioOnly: Boolean,
        videoWidth: Int = 0,
        videoHeight: Int = 0,
    )

    /** Wire up notification/media session transport controls. */
    fun setControls(
        onPlayPause: (() -> Unit)?,
        onSkipForward: (() -> Unit)?,
        onSkipBack: (() -> Unit)?,
        onStop: (() -> Unit)?,
        onSeekTo: ((Long) -> Unit)? = null,
    )

    /** Keep screen on during video playback (prevents display timeout). */
    fun setKeepScreenOn(on: Boolean)

    /** Lock to landscape orientation and hide system bars (Android). No-op on other platforms. */
    fun setFullscreenMode(enabled: Boolean)

    /**
     * Pin the window's screen brightness, overriding system/adaptive control while
     * the player is in front. [level] in 0f..1f locks at that fraction; -1f releases.
     * No-op on platforms without window-level brightness (desktop/web).
     */
    fun setBrightnessLock(level: Float)

    /** Set the active player for the Media3 session bridge (Android). No-op on other platforms. */
    fun setPlayer(player: KobPlayer?)

    /**
     * Hand the media-session bridge back, and its transport with it — but only if [player]
     * is still the one registered.
     *
     * Compare-and-clear because the video surface and the audiobook screen share the slot
     * and can overlap: an unconditional clear on the way out of one takes the other's
     * session down with it. Leaving it set is the other failure, and the one that was
     * happening — the service only tears its Media3 session down once the slot is empty,
     * so a stopped player was kept alive for the life of the process.
     */
    fun releasePlayer(player: KobPlayer)

    /**
     * Request audio focus so other apps duck/pause while we play (Android).
     * Audiobook + video paths get focus via Media3/ExoPlayer automatically; the
     * TTS path uses raw MediaPlayer and needs to ask explicitly. No-op elsewhere.
     */
    fun requestAudioFocus()

    /** Release audio focus acquired via [requestAudioFocus]. */
    fun abandonAudioFocus()
}
