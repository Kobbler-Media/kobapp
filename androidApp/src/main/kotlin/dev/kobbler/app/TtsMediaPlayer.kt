package dev.kobbler.app

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.kobbler.app.platform.PlaybackBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Media3 [SimpleBasePlayer] that mirrors the TTS read-along's transport state
 * onto a [androidx.media3.session.MediaSession]. Without this the foreground
 * notification surfaces as a plain notification — no lockscreen controls, no
 * media tile, no Bluetooth metadata. Source of truth is [PlaybackBridge]'s
 * nowPlaying flow + transport callbacks (set by [dev.kobbler.app.ui.reader.TtsController]).
 *
 * TTS has no meaningful position/duration (unbounded synthesized speech) so we
 * don't advertise seek commands — just play/pause/stop, plus skip-to-next
 * mapped to the controller's skip-sentence action.
 */
@OptIn(UnstableApi::class)
class TtsMediaPlayer(
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
    }

    override fun getState(): State {
        val info = PlaybackBridge.nowPlaying.value
        val mediaItem = MediaItem.Builder()
            .setMediaId("kobbler-tts")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(info.title.ifEmpty { "Kobbler Reader" })
                    .setArtist(info.subtitle ?: "")
                    .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build()
            )
            .build()

        val mediaItemData = MediaItemData.Builder(mediaItemUid)
            .setMediaItem(mediaItem)
            .build()

        val cmds = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            )
        if (PlaybackBridge.onSkipForward != null) {
            cmds.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }

        return State.Builder()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(info.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(listOf(mediaItemData))
            .setCurrentMediaItemIndex(0)
            .setAvailableCommands(cmds.build())
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
        }
        return Futures.immediateVoidFuture()
    }

    fun cleanup() {
        scope.cancel()
    }
}
