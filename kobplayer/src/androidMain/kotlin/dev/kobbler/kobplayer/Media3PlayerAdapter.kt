package dev.kobbler.kobplayer

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bridges the kobplayer [dev.kobbler.kobplayer.Player] interface to
 * [androidx.media3.common.Player] via [SimpleBasePlayer], enabling
 * use with Media3's [androidx.media3.session.MediaSession].
 *
 * State is read from the underlying VLC-backed player's StateFlows.
 * Commands are forwarded to the underlying player.
 *
 * Call [updateMetadata] to set title/artist/artwork for the media session.
 */
@OptIn(UnstableApi::class)
class Media3PlayerAdapter(
    private val player: dev.kobbler.kobplayer.Player,
    /** Optional callback for stop commands (e.g. save progress). Falls back to pause. */
    var onStopRequested: (() -> Unit)? = null,
    private val positionMs: () -> Long = { player.currentMs.value },
    private val durationMs: () -> Long = { player.durationMs.value },
    private val seekTo: (Long) -> Unit = player::seekTo,
    private val seekBackIncrementMs: () -> Long = { C.DEFAULT_SEEK_BACK_INCREMENT_MS },
    private val seekForwardIncrementMs: () -> Long = { C.DEFAULT_SEEK_FORWARD_INCREMENT_MS },
    looper: Looper = Looper.getMainLooper(),
) : SimpleBasePlayer(looper) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mediaItemUid = Any()

    // Metadata (updated from app layer)
    private var title: CharSequence = ""
    private var artist: CharSequence = ""
    private var artworkData: ByteArray? = null

    init {
        // Invalidate when player state changes
        scope.launch { player.state.collect { invalidateState() } }
        scope.launch { player.durationMs.collect { invalidateState() } }
        scope.launch { player.isBuffering.collect { invalidateState() } }
        scope.launch { player.playbackSpeed.collect { invalidateState() } }

        // Periodic sync for position (handles in-app seeks not routed through Media3)
        scope.launch {
            while (true) {
                delay(5_000)
                invalidateState()
            }
        }
    }

    /** Update media metadata shown in notification/lock screen. */
    fun updateMetadata(title: String, artist: String?, artworkData: ByteArray?) {
        if (title == this.title && (artist ?: "") == this.artist && artworkData === this.artworkData) return
        this.title = title
        this.artist = artist ?: ""
        this.artworkData = artworkData
        invalidateState()
    }

    override fun getState(): State {
        val vlcState = player.state.value
        val (playbackState, playWhenReady) = when (vlcState) {
            PlayerState.IDLE -> Player.STATE_IDLE to false
            PlayerState.LOADING, PlayerState.BUFFERING -> Player.STATE_BUFFERING to true
            PlayerState.PLAYING -> Player.STATE_READY to true
            PlayerState.PAUSED -> Player.STATE_READY to false
            PlayerState.FINISHED -> Player.STATE_ENDED to false
            PlayerState.ERROR -> Player.STATE_IDLE to false
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId("current")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build()
            )
            .build()

        val durationUs = durationMs() * 1000

        val mediaItemData = MediaItemData.Builder(mediaItemUid)
            .setMediaItem(mediaItem)
            .setDurationUs(durationUs)
            .build()

        return State.Builder()
            .setPlaybackState(playbackState)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(positionMs())
            .setSeekBackIncrementMs(seekBackIncrementMs())
            .setSeekForwardIncrementMs(seekForwardIncrementMs())
            .setPlaylist(listOf(mediaItemData))
            .setCurrentMediaItemIndex(0)
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_STOP,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_FORWARD,
                        Player.COMMAND_SEEK_BACK,
                        Player.COMMAND_SET_SPEED_AND_PITCH,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    )
                    .build()
            )
            .setPlaybackParameters(PlaybackParameters(player.playbackSpeed.value))
            .setIsLoading(
                player.isBuffering.value
                    && playbackState != Player.STATE_IDLE
                    && playbackState != Player.STATE_ENDED
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        // Only honor play commands if the player was explicitly started by the user
        // (not during muted preload). Android media routing, Bluetooth, and lock screen
        // can trigger this, which would unmute and start preloaded content unexpectedly.
        if (playWhenReady) {
            if (!player.isMuted.value) player.play()
        } else {
            player.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: @Player.Command Int,
    ): ListenableFuture<*> {
        seekTo(positionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        onStopRequested?.invoke() ?: player.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(
        playbackParameters: PlaybackParameters,
    ): ListenableFuture<*> {
        player.setPlaybackSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    /** Release coroutine scope. Call when the adapter is no longer needed. */
    fun cleanup() {
        scope.cancel()
    }
}
