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
    // Read by the media session in place of the player's own clock.
    var timeline: PlaybackTimeline? = null

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
        isAudioOnly: Boolean,
        videoWidth: Int = 0,
        videoHeight: Int = 0,
    ) {
        val wasPlaying = _nowPlaying.value.isPlaying
        _nowPlaying.value = NowPlayingInfo(title, subtitle, coverUrl, isPlaying, isAudioOnly, videoWidth, videoHeight)
        if (isPlaying != wasPlaying) host?.updatePipActions(isPlaying)
    }

    fun setControls(
        onPlayPause: (() -> Unit)?,
        onSkipForward: (() -> Unit)?,
        onSkipBack: (() -> Unit)?,
        onStop: (() -> Unit)?,
        timeline: PlaybackTimeline? = null,
    ) {
        this.onPlayPause = onPlayPause
        this.onSkipForward = onSkipForward
        this.onSkipBack = onSkipBack
        this.onStop = onStop
        this.timeline = timeline
    }

    fun clearNowPlaying() {
        _nowPlaying.value = NowPlayingInfo()
        onPlayPause = null
        onSkipForward = null
        onSkipBack = null
        onStop = null
        timeline = null
        playerFlow.value = null
    }
}
