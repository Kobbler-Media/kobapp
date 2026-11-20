package dev.kobbler.app

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.kobbler.app.platform.PlaybackBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TIMELINE_REFRESH_MS = 5_000L

/**
 * Media3 [SimpleBasePlayer] for what has transport but no kobplayer.Player on this device:
 * a read-aloud, or a cast this device drives itself (DLNA). Without it the foreground
 * notification is a plain one — no lock screen controls, media tile or Bluetooth metadata.
 * Source of truth is [PlaybackBridge]'s nowPlaying, transport callbacks and timeline.
 *
 * A read-aloud has no timeline (unbounded synthesized speech), so it offers play/pause/stop
 * and skip-to-next mapped to the next sentence. A cast has one, and offers seeking on it.
 */
@OptIn(UnstableApi::class)
class BridgeMediaPlayer(
    looper: Looper = Looper.getMainLooper(),
) : SimpleBasePlayer(looper) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mediaItemUid = Any()

    var artworkData: ByteArray? = null
        set(value) {
            if (value === field) return
            field = value
            invalidateState()
        }

    init {
        scope.launch { PlaybackBridge.nowPlaying.collect { invalidateState() } }
        // A cast's playhead moves on its own clock, and jumps (a skipped intro) unannounced.
        scope.launch {
            while (true) {
                delay(TIMELINE_REFRESH_MS)
                if (PlaybackBridge.timeline != null) invalidateState()
            }
        }
    }

    override fun getState(): State {
        val info = PlaybackBridge.nowPlaying.value
        val timeline = PlaybackBridge.timeline
        val mediaItem = MediaItem.Builder()
            .setMediaId("kobbler-bridge")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(info.title.ifEmpty { "Kobbler" })
                    .setArtist(info.subtitle ?: "")
                    .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build()
            )
            .build()

        val mediaItemData = MediaItemData.Builder(mediaItemUid)
            .setMediaItem(mediaItem)
            .apply { timeline?.let { setDurationUs(Util.msToUs(it.durationMs())) } }
            .build()

        val cmds = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            )
        if (timeline != null) {
            cmds.addAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
        } else if (PlaybackBridge.onSkipForward != null) {
            cmds.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }

        return State.Builder()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(info.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(listOf(mediaItemData))
            .setCurrentMediaItemIndex(0)
            .setAvailableCommands(cmds.build())
            .apply {
                if (timeline != null) {
                    setContentPositionMs { timeline.positionMs() }
                    setSeekBackIncrementMs(timeline.skipBackMs)
                    setSeekForwardIncrementMs(timeline.skipForwardMs)
                }
            }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady != PlaybackBridge.nowPlaying.value.isPlaying) PlaybackBridge.onPlayPause?.invoke()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        PlaybackBridge.onStop?.invoke()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: @Player.Command Int,
    ): ListenableFuture<*> {
        if (seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) {
            PlaybackBridge.onSkipForward?.invoke()
        } else {
            PlaybackBridge.timeline?.seekTo?.invoke(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    fun cleanup() {
        scope.cancel()
    }
}
