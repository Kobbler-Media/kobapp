package dev.kobbler.app.castconnect

import android.os.SystemClock
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.tv.media.MediaManager
import dev.kobbler.app.castreceiver.ReceiverNow

private const val RebroadcastMs = 5_000L

internal class MediaStatusSync(private val media: MediaManager) {
    private var last: ReceiverNow? = null
    private var broadcastAt = 0L

    fun apply(now: ReceiverNow?) {
        val previous = last
        last = now
        if (now == null) {
            if (previous == null) return
            media.mediaStatusModifier
                .setPlayerState(MediaStatus.PLAYER_STATE_IDLE)
                .setIdleReason(MediaStatus.IDLE_REASON_FINISHED)
            return broadcast()
        }
        val moved = previous != null && previous.mediaId != now.mediaId
        if (moved) media.setDataFromLoad(loadOf(now))
        val due = SystemClock.elapsedRealtime() - broadcastAt >= RebroadcastMs
        if (!moved && !due && previous?.playing == now.playing) return
        media.mediaStatusModifier
            .setPlayerState(if (now.playing) MediaStatus.PLAYER_STATE_PLAYING else MediaStatus.PLAYER_STATE_PAUSED)
            .setStreamPosition(now.positionMs)
        broadcast()
    }

    private fun broadcast() {
        broadcastAt = SystemClock.elapsedRealtime()
        media.broadcastMediaStatus()
    }

    private fun loadOf(now: ReceiverNow): MediaLoadRequestData {
        val metadata = MediaMetadata(
            if (now.mediaType == "episode") MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE,
        ).apply {
            putString(MediaMetadata.KEY_TITLE, now.title)
            now.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
        }
        val info = MediaInfo.Builder(now.mediaId.orEmpty())
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("video/mp4")
            .setStreamDuration(now.durationMs)
            .setMetadata(metadata)
            .build()
        return MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(now.playing).setCurrentTime(now.positionMs).build()
    }
}
