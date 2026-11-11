package dev.kobbler.kobplayer

import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

internal class LibVlcBinding(val player: MediaPlayer) : VlcBinding {
    // Released with the player: releasing it under a call still running in a stuck one is a native crash.
    var media: Media? = null

    fun start(next: Media) {
        val previous = media
        media = next
        player.media = next
        previous?.release()
        player.play()
    }

    fun release() {
        player.stopAsync()
        player.release()
        media?.release()
        media = null
    }

    override val inputState: VlcInputState
        get() = when (player.playerState) {
            MediaPlayer.State.Opening -> VlcInputState.Opening
            MediaPlayer.State.Playing -> VlcInputState.Playing
            MediaPlayer.State.Paused -> VlcInputState.Paused
            MediaPlayer.State.Stopping -> VlcInputState.Stopping
            MediaPlayer.State.Stopped -> VlcInputState.Stopped
            MediaPlayer.State.Error -> VlcInputState.Error
            else -> VlcInputState.Idle
        }

    override val timeUs: Long get() = player.time

    override fun play() = player.play()

    // libvlcjni's pause() is libVLC's set_pause, not its toggle; resume() is kobvlc's.
    override fun setPause(paused: Boolean) = if (paused) player.pause() else player.resume()

    override fun stop() = player.stopAsync()

    override fun setTime(us: Long, fast: Boolean) {
        player.setTime(us, fast)
    }

    override fun setRate(rate: Float) {
        player.rate = rate
    }

    override fun tracks(type: VlcTrackType): List<TrackInfo> =
        player.getTracks(type.libVlc).orEmpty().mapIndexed { index, track ->
            TrackInfo(track.id, track.name ?: "Track ${index + 1}")
        }

    override fun selectedTrackId(type: VlcTrackType): String? = player.getSelectedTrack(type.libVlc)?.id

    override fun selectTrack(type: VlcTrackType, id: String) {
        player.selectTrack(id)
    }

    override fun deselect(type: VlcTrackType) = player.unselectTrackType(type.libVlc)

    override fun chapters(): List<Chapter> = player.getChapters(-1).orEmpty().map {
        Chapter(name = it.name, offsetMs = it.timeOffset / 1000, durationMs = it.duration / 1000)
    }

    override fun setChapter(index: Int) {
        player.chapter = index
    }

    override fun sleep(ms: Long) = Thread.sleep(ms)
}

internal val VlcTrackType.libVlc: Int
    get() = when (this) {
        VlcTrackType.Audio -> IMedia.Track.Type.Audio
        VlcTrackType.Video -> IMedia.Track.Type.Video
        VlcTrackType.Text -> IMedia.Track.Type.Text
    }
