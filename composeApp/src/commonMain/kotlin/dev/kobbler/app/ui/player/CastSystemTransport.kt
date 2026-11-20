package dev.kobbler.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.kobbler.app.platform.CastFeature
import dev.kobbler.app.platform.CastSender
import dev.kobbler.app.platform.PlaybackLifecycle
import dev.kobbler.app.platform.PlaybackTimeline

internal data class CastNowPlaying(val title: String, val subtitle: String?, val isPlaying: Boolean)

/** A cast whose sender SDK puts nothing on the lock screen, as a DLNA one: the app's own media session shows it. */
internal fun castNeedsSystemTransport(cast: CastSender, gps: GlobalPlayerState): Boolean {
    val session = gps.castSession ?: return false
    return !session.connecting && cast.device.value != null && CastFeature.SystemTransport !in cast.features
}

/** The system transport for a cast: everything goes through the same hooks as the cast controller on screen. */
internal class CastSystemControls(private val gps: GlobalPlayerState) {
    val timeline = PlaybackTimeline(
        positionMs = { gps.castSession?.positionMs ?: 0L },
        durationMs = { gps.castSession?.durationMs ?: 0L },
        seekTo = { ms -> gps.onCastSeek?.invoke(ms.coerceIn(0L, timelineEnd())) },
        skipBackMs = VIDEO_SKIP_MS,
        skipForwardMs = VIDEO_SKIP_MS,
    )

    private fun timelineEnd() = gps.castSession?.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE

    fun nowPlaying(): CastNowPlaying? = gps.castSession?.let { CastNowPlaying(it.mediaTitle, it.subtitle, it.isPlaying) }

    fun onPlayPause() { gps.onCastPlayPause?.invoke() }
    fun onSkipBack() = timeline.seekTo(timeline.positionMs() - VIDEO_SKIP_MS)
    fun onSkipForward() = timeline.seekTo(timeline.positionMs() + VIDEO_SKIP_MS)
    fun onStop() { gps.onCastDisconnect?.invoke() }
}

@Composable
fun CastSystemTransport(cast: CastSender, gps: GlobalPlayerState) {
    if (!castNeedsSystemTransport(cast, gps)) return
    val controls = remember(gps) { CastSystemControls(gps) }
    DisposableEffect(controls) {
        PlaybackLifecycle.startBackgroundPlayback()
        PlaybackLifecycle.setControls(
            onPlayPause = controls::onPlayPause,
            onSkipForward = controls::onSkipForward,
            onSkipBack = controls::onSkipBack,
            onStop = controls::onStop,
            timeline = controls.timeline,
        )
        onDispose {
            PlaybackLifecycle.setControls(null, null, null, null)
            PlaybackLifecycle.stopBackgroundPlayback()
        }
    }
    val now = controls.nowPlaying() ?: return
    val positionMs = gps.castSession?.positionMs ?: 0L
    val durationMs = gps.castSession?.durationMs ?: 0L
    LaunchedEffect(now, gps.posterUrl, positionMs / 1000, durationMs) {
        PlaybackLifecycle.updateNowPlaying(
            title = now.title,
            subtitle = now.subtitle,
            coverUrl = gps.posterUrl,
            isPlaying = now.isPlaying,
            currentMs = positionMs,
            durationMs = durationMs,
            isAudioOnly = false,
        )
    }
}
