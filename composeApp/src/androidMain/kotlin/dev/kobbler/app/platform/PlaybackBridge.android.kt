package dev.kobbler.app.platform

import android.content.Context
import android.content.Intent
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared playback metadata for the foreground service notification.
 * Player screens write here via PlaybackLifecycle; the service reads to build notifications.
 */
data class NowPlayingInfo(
    val title: String = "",
    val subtitle: String? = null,
    val coverUrl: String? = null,
    val isPlaying: Boolean = false,
    val currentMs: Long = 0,
    val durationMs: Long = 0,
    val isAudioOnly: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

/**
 * Android-specific playback service bridge.
 * Starts a foreground service to keep audio playing with the screen off,
 * and provides media notification controls.
 */
object PlaybackBridge {
    private var context: Context? = null

    // Observable state for foreground service notification
    private val _nowPlaying = MutableStateFlow(NowPlayingInfo())
    val nowPlaying: StateFlow<NowPlayingInfo> = _nowPlaying.asStateFlow()

    // Transport control callbacks (set by player screens, invoked by notification/media session/PiP)
    var onPlayPause: (() -> Unit)? = null
    var onSkipForward: (() -> Unit)? = null
    var onSkipBack: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null

    // PiP mode observable
    val isInPipMode = MutableStateFlow(false)

    // Active player instance (for Media3 session bridge)
    val playerFlow = MutableStateFlow<Player?>(null)

    /**
     * Host interface for Activity-level operations that require window/PiP access.
     * Implemented by MainActivity.
     */
    interface PlaybackHost {
        fun setKeepScreenOn(on: Boolean)
        fun setVideoPlaying(playing: Boolean)
        fun updatePipActions(isPlaying: Boolean)
        fun setFullscreenMode(enabled: Boolean)
        fun setBrightnessLock(level: Float)
    }

    var host: PlaybackHost? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun appContext(): Context? = context

    fun startService() {
        val ctx = context ?: return
        try {
            val intent = Intent().apply {
                setClassName(ctx.packageName, "dev.kobbler.app.PlaybackService")
            }
            ctx.startForegroundService(intent)
        } catch (_: Exception) {}
    }

    fun stopService() {
        val ctx = context ?: return
        try {
            val intent = Intent().apply {
                setClassName(ctx.packageName, "dev.kobbler.app.PlaybackService")
            }
            ctx.stopService(intent)
        } catch (_: Exception) {}
    }

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
    ) {
        _nowPlaying.value = NowPlayingInfo(title, subtitle, coverUrl, isPlaying, currentMs, durationMs, isAudioOnly, videoWidth, videoHeight)
        host?.updatePipActions(isPlaying)
    }

    fun setControls(
        onPlayPause: (() -> Unit)?,
        onSkipForward: (() -> Unit)?,
        onSkipBack: (() -> Unit)?,
        onStop: (() -> Unit)?,
        onSeekTo: ((Long) -> Unit)? = null,
    ) {
        this.onPlayPause = onPlayPause
        this.onSkipForward = onSkipForward
        this.onSkipBack = onSkipBack
        this.onStop = onStop
        this.onSeekTo = onSeekTo
    }

    fun clearNowPlaying() {
        _nowPlaying.value = NowPlayingInfo()
        onPlayPause = null
        onSkipForward = null
        onSkipBack = null
        onStop = null
        onSeekTo = null
        playerFlow.value = null
    }
}
