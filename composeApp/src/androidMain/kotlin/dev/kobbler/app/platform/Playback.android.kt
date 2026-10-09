package dev.kobbler.app.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import dev.kobbler.kobplayer.Player as KobPlayer
import kotlinx.coroutines.flow.StateFlow

actual object PlaybackLifecycle {
    private var focusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var pausedByFocusLoss = false

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            // For speech, ducking would lose words — pause instead.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (PlaybackBridge.nowPlaying.value.isPlaying) {
                    pausedByFocusLoss = true
                    PlaybackBridge.onPlayPause?.invoke()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss && !PlaybackBridge.nowPlaying.value.isPlaying) {
                    pausedByFocusLoss = false
                    PlaybackBridge.onPlayPause?.invoke()
                }
            }
        }
    }

    actual fun startBackgroundPlayback() {
        PlaybackBridge.startService()
    }

    actual fun stopBackgroundPlayback() {
        PlaybackBridge.clearNowPlaying()
        PlaybackBridge.stopService()
    }

    actual fun setVideoPlaying(playing: Boolean) {
        PlaybackBridge.host?.setVideoPlaying(playing)
    }

    actual fun isInPipMode(): Boolean = PlaybackBridge.isInPipMode.value

    actual val isInPipModeFlow: StateFlow<Boolean> get() = PlaybackBridge.isInPipMode

    actual fun updateNowPlaying(
        title: String,
        subtitle: String?,
        coverUrl: String?,
        isPlaying: Boolean,
        currentMs: Long,
        durationMs: Long,
        isAudioOnly: Boolean,
        videoWidth: Int,
        videoHeight: Int,
    ) {
        PlaybackBridge.updateNowPlaying(title, subtitle, coverUrl, isPlaying, currentMs, durationMs, isAudioOnly, videoWidth, videoHeight)
    }

    actual fun setControls(
        onPlayPause: (() -> Unit)?,
        onSkipForward: (() -> Unit)?,
        onSkipBack: (() -> Unit)?,
        onStop: (() -> Unit)?,
        onSeekTo: ((Long) -> Unit)?,
    ) {
        PlaybackBridge.setControls(onPlayPause, onSkipForward, onSkipBack, onStop, onSeekTo)
    }

    actual fun setKeepScreenOn(on: Boolean) {
        PlaybackBridge.host?.setKeepScreenOn(on)
    }

    actual fun setFullscreenMode(enabled: Boolean) {
        PlaybackBridge.host?.setFullscreenMode(enabled)
    }

    actual fun setBrightnessLock(level: Float) {
        PlaybackBridge.host?.setBrightnessLock(level)
    }

    actual fun setPlayer(player: KobPlayer?) {
        PlaybackBridge.playerFlow.value = player
    }

    actual fun releasePlayer(player: KobPlayer) {
        if (PlaybackBridge.playerFlow.value !== player) return
        PlaybackBridge.playerFlow.value = null
        PlaybackBridge.setControls(null, null, null, null)
    }

    actual fun requestAudioFocus() {
        val ctx = PlaybackBridge.appContext() ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        pausedByFocusLoss = false
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) return
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { onFocusChange(it) }
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            if (legacyFocusListener != null) return
            val l = AudioManager.OnAudioFocusChangeListener { change -> onFocusChange(change) }
            legacyFocusListener = l
            @Suppress("DEPRECATION")
            am.requestAudioFocus(l, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    actual fun abandonAudioFocus() {
        val ctx = PlaybackBridge.appContext() ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        pausedByFocusLoss = false
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            legacyFocusListener?.let {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(it)
            }
            legacyFocusListener = null
        }
    }
}
