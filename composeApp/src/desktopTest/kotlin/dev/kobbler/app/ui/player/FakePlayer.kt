package dev.kobbler.app.ui.player

import dev.kobbler.kobplayer.AspectRatio
import dev.kobbler.kobplayer.Chapter
import dev.kobbler.kobplayer.MediaSource
import dev.kobbler.kobplayer.NativePlayer
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.PlayerState
import dev.kobbler.kobplayer.TrackInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A player that records what it was told to do. [durationMs] stays at zero until a test
 * publishes one, which is how a source that has not been parsed yet behaves.
 */
class FakePlayer : Player {
    // Written from the player's teardown scope and read from the test thread — a plain
    // list threw ConcurrentModificationException at whichever assertion happened to be
    // iterating when a repair landed.
    val calls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    var loadedUrl: String? = null
    var loadedSource: MediaSource.Url? = null

    /** A play that never becomes playback — a reaped session answers exactly like this. */
    var playStalls: Boolean = false

    /**
     * The demuxer declined the open-time position, which is what an HTTP source it has
     * not yet decided is seekable does.
     */
    var startTimeIgnored: Boolean = false

    /** A source this answers true for errors on play, the way an unopenable stream does. */
    var failsToOpen: (String) -> Boolean = { false }

    /** Accept this many seeks without performing them, the way a dropped seek reads. */
    var seeksDropped: Int = 0

    /** Commands that arrived off the main thread, which a libVLC-backed player must never see. */
    val offMainCalls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private fun record(call: String) {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) offMainCalls += call
        calls += call
    }

    override val state = MutableStateFlow(PlayerState.IDLE)
    override val isPlaying = MutableStateFlow(false)
    override val currentMs = MutableStateFlow(0L)
    override val durationMs = MutableStateFlow(0L)
    override val progress = MutableStateFlow(0f)
    override val error = MutableStateFlow<Throwable?>(null)
    override val isBuffering = MutableStateFlow(false)
    override val bufferPercent = MutableStateFlow(0f)
    override val volume = MutableStateFlow(1f)
    override val maxVolume = 2f
    override val isMuted = MutableStateFlow(false)
    override val audioTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    override val selectedAudioTrack = MutableStateFlow<TrackInfo?>(null)
    override val subtitleTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    override val selectedSubtitleTrack = MutableStateFlow<TrackInfo?>(null)
    override val chapters = MutableStateFlow<List<Chapter>>(emptyList())
    override val currentChapter = MutableStateFlow<Chapter?>(null)
    override val playbackSpeed = MutableStateFlow(1f)
    override val aspectRatio = MutableStateFlow(AspectRatio.FIT)
    override val videoSize = MutableStateFlow(0 to 0)
    override val videoOutputReady = MutableStateFlow(true)

    override fun load(source: MediaSource) {
        loadedSource = source as? MediaSource.Url
        loadedUrl = loadedSource?.url
        error.value = null
        calls += "load"
        currentMs.value = if (startTimeIgnored) 0L
        else (loadedSource?.startTimeSec ?: 0).toLong() * 1000
    }
    // A real player answers a play with a PLAYING state, and the app waits for that to
    // decide a stream has actually started — a fake that never reports it leaves every
    // session stuck in its opening posture, with the transport suppressed.
    override fun play() {
        calls += "play"
        if (loadedUrl?.let(failsToOpen) == true) {
            error.value = RuntimeException("VLC could not open $loadedUrl")
            return
        }
        if (playStalls) return
        isPlaying.value = true
        state.value = PlayerState.PLAYING
    }
    override fun stop() { calls += "stop"; isPlaying.value = false }
    override fun pause() { calls += "pause"; isPlaying.value = false }
    override fun togglePlayPause() = if (isPlaying.value) pause() else play()
    override fun seekTo(ms: Long) {
        record("seek:$ms")
        // Like the real players: the position is reported optimistically, so a seek that
        // is dropped has to be caught by reading the clock again afterwards.
        if (seeksDropped > 0) {
            seeksDropped--
            return
        }
        currentMs.value = ms
    }
    override fun warmVideoOutput() { calls += "warm" }
    override fun skip(deltaMs: Long) = seekTo(currentMs.value + deltaMs)
    override fun setVolume(volume: Float) { this.volume.value = volume }
    override fun setMuted(muted: Boolean) { isMuted.value = muted }
    override fun volumeUp(step: Float) { }
    override fun volumeDown(step: Float) { }
    override fun setPlaybackSpeed(speed: Float) { playbackSpeed.value = speed }
    override fun selectAudioTrack(track: TrackInfo?) {
        record("audio:${track?.id}")
        selectedAudioTrack.value = track
    }
    override fun selectSubtitleTrack(track: TrackInfo?) {
        record("subtitle:${track?.id}")
        selectedSubtitleTrack.value = track
    }
    override fun setAspectRatio(ratio: AspectRatio) { aspectRatio.value = ratio }
    override fun setPreloading(preloading: Boolean) { calls += "preloading:$preloading" }
    override fun setMatchDisplayRefresh(enabled: Boolean) { calls += "matchDisplayRefresh:$enabled" }
    override fun setChapter(chapter: Chapter) { currentChapter.value = chapter }
    override suspend fun takeScreenshot(path: String) { }
    override fun release() { }
    override val native: NativePlayer get() = throw UnsupportedOperationException()
}
