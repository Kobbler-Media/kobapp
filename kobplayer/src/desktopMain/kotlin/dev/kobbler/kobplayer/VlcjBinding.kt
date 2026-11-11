package dev.kobbler.kobplayer

import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.base.Track
import uk.co.caprica.vlcj.player.base.TrackList

internal class VlcjBinding(val player: MediaPlayer) : VlcBinding {
    override val inputState: VlcInputState
        get() = when (player.status().state()) {
            State.OPENING -> VlcInputState.Opening
            State.PLAYING -> VlcInputState.Playing
            State.PAUSED -> VlcInputState.Paused
            State.STOPPING -> VlcInputState.Stopping
            State.STOPPED -> VlcInputState.Stopped
            State.ERROR -> VlcInputState.Error
            State.NOTHING_SPECIAL, null -> VlcInputState.Idle
        }

    override val timeUs: Long get() = player.status().time()

    override fun play() {
        player.controls().play()
    }

    override fun setPause(paused: Boolean) = player.controls().setPause(paused)

    override fun stop() {
        player.controls().stop()
    }

    override fun setTime(us: Long, fast: Boolean) {
        player.controls().setTime(us, fast)
    }

    override fun setRate(rate: Float) {
        player.controls().setRate(rate)
    }

    override fun tracks(type: VlcTrackType): List<TrackInfo> = when (type) {
        VlcTrackType.Audio -> trackInfos(player.tracks().audioTracks())
        VlcTrackType.Video -> trackInfos(player.tracks().videoTracks())
        VlcTrackType.Text -> trackInfos(player.tracks().textTracks())
    }

    override fun selectedTrackId(type: VlcTrackType): String? = when (type) {
        VlcTrackType.Audio -> player.tracks().selectedAudioTrack()
        VlcTrackType.Video -> player.tracks().selectedVideoTrack()
        VlcTrackType.Text -> player.tracks().selectedTextTrack()
    }?.use { it.trackId() }

    override fun selectTrack(type: VlcTrackType, id: String) = player.tracks().select(type.vlcj, id)

    override fun deselect(type: VlcTrackType) = player.tracks().deselect(type.vlcj)

    override fun chapters(): List<Chapter> = player.chapters().allDescriptions().flatMap { title ->
        title.map { Chapter(name = it.name(), offsetMs = it.offset() / 1000, durationMs = it.duration() / 1000) }
    }

    override fun setChapter(index: Int) = player.chapters().setChapter(index)

    override fun sleep(ms: Long) = Thread.sleep(ms)
}

private val VlcTrackType.vlcj: TrackType
    get() = when (this) {
        VlcTrackType.Audio -> TrackType.AUDIO
        VlcTrackType.Video -> TrackType.VIDEO
        VlcTrackType.Text -> TrackType.TEXT
    }

private fun <T : Track> trackInfos(list: TrackList<T>): List<TrackInfo> = list.use {
    it.tracks().mapIndexed { index, track -> TrackInfo(track.trackId(), track.name() ?: "Track ${index + 1}") }
}

internal inline fun <L : TrackList<*>, R> L.use(block: (L) -> R): R = try { block(this) } finally { release() }

internal inline fun <T : Track, R> T.use(block: (T) -> R): R = try { block(this) } finally { release() }
