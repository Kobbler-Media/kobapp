package dev.kobbler.kobplayer

import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.base.Capabilties
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.VideoTrack
import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext

/** libVLC 4 through vlcj 5, drawing into our own buffers. */
internal class DesktopPlayer(
    parentContext: CoroutineContext,
    config: PlayerConfig,
    vlcLogFile: String? = System.getenv("KOBPLAYER_VLC_LOG"),
) : VlcPlayer<VlcjBinding>(parentContext, config) {

    private val factory = createPlayerLock.withLock {
        MediaPlayerFactory(libVlcDiscovery, config.readAhead.vlcOptions + if (config.verboseLogging) listOf("-vv") else emptyList())
    }

    private val logFile = vlcLogFile?.let { VlcLogFile(factory, it) }

    internal val video: VlcVideoSink? = if (!config.audioOnly) {
        VlcVideoSink(
            onPicture = { layout ->
                pictureLayout = layout
                updateVideoSize()
            },
        )
    } else null

    // The picture can be negotiated before the track list has its aspect ratio; the size is
    // worked out again when the track catches up.
    @Volatile private var pictureLayout: FrameLayout? = null

    override fun updateVideoSize() {
        val layout = pictureLayout ?: return
        _videoSize.value = withVideoTrack { track ->
            displaySize(layout.width, layout.height, track?.sampleAspectRatio() ?: 0, track?.sampleAspectRatioBase() ?: 0)
        }
    }

    override val native: NativePlayer get() = NativePlayer.Vlcj(vlcPlayer)

    val vlcPlayer: MediaPlayer get() = binding.player

    private val gain = GainEnvelope(scope) { vlcPlayer.audio().setVolume(it) }
    internal val gainLevel: StateFlow<Int> get() = gain.level

    // Only the current player's: one taken over while stuck can still report on the title it was stuck in.
    private val events = object : MediaPlayerEventAdapter() {
        override fun opening(mediaPlayer: MediaPlayer) {
            if (mediaPlayer === vlcPlayer) onOpening()
        }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
            if (mediaPlayer === vlcPlayer) onLength(newLength)
        }

        override fun trackAdded(mediaPlayer: MediaPlayer, trackType: TrackType, trackId: String) {
            if (mediaPlayer === vlcPlayer) onTracksChanged()
        }

        override fun trackUpdated(mediaPlayer: MediaPlayer, trackType: TrackType, trackId: String) {
            if (mediaPlayer === vlcPlayer) onTracksChanged()
        }

        override fun trackSelectionChanged(mediaPlayer: MediaPlayer, type: TrackType, unselectedId: String?, selectedId: String?) {
            if (mediaPlayer === vlcPlayer) onTracksChanged()
        }

        // The initial volume can lose to VLC's own readiness reset.
        override fun mediaPlayerReady(mediaPlayer: MediaPlayer?) {
            if (mediaPlayer !== vlcPlayer) return
            vlc {
                gain.reapply()
                if (_isMuted.value) player.audio().setMute(true)
            }
        }

        override fun playing(mediaPlayer: MediaPlayer) {
            if (mediaPlayer === vlcPlayer) onPlaying()
        }

        override fun capabilitiesChanged(mediaPlayer: MediaPlayer, oldCapabilities: Capabilties, newCapabilities: Capabilties) {
            if (mediaPlayer === vlcPlayer) onPausable(newCapabilities.canPause())
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            if (mediaPlayer === vlcPlayer) onPaused()
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            if (mediaPlayer === vlcPlayer) onStopping()
        }

        override fun error(mediaPlayer: MediaPlayer) {
            if (mediaPlayer === vlcPlayer) onError()
        }

        override fun bufferingChanged(mediaPlayer: MediaPlayer, newCache: Float) {
            if (mediaPlayer === vlcPlayer) onBuffering(newCache)
        }

        override fun positionChanged(mediaPlayer: MediaPlayer, newTimeUs: Long, newPosition: Double) {
            if (mediaPlayer === vlcPlayer) onPosition(newTimeUs)
        }
    }

    override fun newEngine(): CommandLane.Engine<VlcjBinding> {
        var runner: Thread? = null
        val thread = Executors.newSingleThreadExecutor { Thread(it, "kobplayer-vlc").apply { isDaemon = true; runner = this } }
        val player = if (config.audioOnly) {
            factory.mediaPlayers().newMediaPlayer()
        } else {
            factory.mediaPlayers().newEmbeddedMediaPlayer()
        }
        // vlcj 5.0.0-M6's release() throws before freeing the player unless its clock
        // was ever watched.
        player.time().startWatching()
        player.events().addMediaPlayerEventListener(events)
        video?.attach(player)
        return CommandLane.Engine(
            VlcjBinding(player),
            thread.asCoroutineDispatcher(),
            stuckIn = { runner?.stackTrace?.take(STUCK_FRAMES)?.joinToString("\n") { "at $it" }.orEmpty() },
            // What sticks is vlcj's stop, waiting on a latch for an event that may never come:
            // it gives up when interrupted.
            unstick = { runner?.interrupt() },
        ) {
            it.player.events().removeMediaPlayerEventListener(events)
            it.player.release()
            video?.detach(it.player)
            thread.shutdown()
        }
    }

    init {
        scope.coroutineContext[Job]!!.invokeOnCompletion { release() }
        if (video != null) {
            scope.launch {
                _state.collect { playerState ->
                    video.renderingEnabled = playerState == PlayerState.PLAYING
                            || playerState == PlayerState.BUFFERING
                            || playerState == PlayerState.LOADING
                }
            }
        }
    }

    @Volatile
    private var decoding: VideoDecoding = if (config.hardwareAcceleration) VideoDecoding.Auto else VideoDecoding.Software

    override fun setVideoDecoding(decoding: VideoDecoding) {
        this.decoding = decoding
    }

    @Volatile private var device: DecoderDevice? = null

    internal val gpuDecodeRequested get() = device?.let { it.name != "none" } ?: false

    override fun beforeOpen(source: MediaSource.Url) {
        device = decoderDevice(source, decoding)
        gain.set(0)
    }

    override fun VlcjBinding.startTitle(source: MediaSource.Url) {
        player.setDecoderDevice(device ?: decoderDevice(source, decoding))
        player.media().play(vlcMrl(source.url), *vlcMediaOptions(source, config).toTypedArray())
        // New media may reset audio state; a muted preload must not leak.
        if (_isMuted.value) player.audio().setMute(true)
    }

    override fun onAudible() = gain.rampTo(FADE_IN_MS, ::userVolume)

    override fun play() {
        if (_state.value == PlayerState.PLAYING) gain.rampTo(FADE_IN_MS, ::userVolume)
        super.play()
    }

    override fun fadeOutThen(pause: () -> Unit) {
        if (_state.value == PlayerState.PLAYING) gain.rampTo(FADE_OUT_MS, { 0 }, pause) else pause()
    }

    override fun onSeek(ms: Long) {
        video?.allowFrames(2)
    }

    override fun onStopped() {
        device = null
        video?.clear()
    }

    override fun setVolume(volume: Float) {
        _volume.value = volume.coerceIn(0f, maxVolume)
        applyUserGain()
    }

    override fun setNormalizationGain(gain: Float) {
        normalization = gain
        applyUserGain()
    }

    // A ramp reads the volume on every step, and a paused player sits at zero until
    // the next fade-in, so only a steady playing gain is set directly.
    private fun applyUserGain() {
        val audible = _state.value == PlayerState.PLAYING || _state.value == PlayerState.BUFFERING
        if (audible && !isPreloading.value && !gain.isRamping) gain.set(userVolume())
    }

    override fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        vlcPlayer.audio().setMute(muted)
    }

    override fun setPreloading(preloading: Boolean) {
        if (isPreloading.value == preloading) return
        isPreloading.value = preloading
        if (preloading) gain.set(0) else gain.rampTo(FADE_IN_MS, ::userVolume)
    }

    override fun selectSubtitleTrack(track: TrackInfo?) {
        super.selectSubtitleTrack(track)
        val source = loadedSource ?: return
        val ordinal = _subtitleTracks.value.indexOf(track)
        // VLC can only draw subtitles into a picture the CPU decoded — reopen there.
        if (track != null && gpuDecodeRequested && ordinal >= 0) {
            load(source.copy(subtitleOrdinal = ordinal, subtitlesOff = false, startTimeSec = (_currentMs.value / 1000).toInt()))
            _selectedSubtitleTrack.value = track
        }
    }

    override suspend fun takeScreenshot(path: String) {
        vlc { player.snapshots().save(File(path)) }
    }

    override fun release() {
        if (released) return
        released = true
        _currentMs.value = 0L
        video?.clear()
        lane.close { logFile?.close() }
        scope.cancel()
    }

    private fun <R> withVideoTrack(read: (VideoTrack?) -> R): R {
        val selected = vlcPlayer.tracks().selectedVideoTrack()
        return if (selected != null) selected.use(read) else vlcPlayer.tracks().videoTracks().use { read(it.tracks().firstOrNull()) }
    }

    companion object {
        private val createPlayerLock = ReentrantLock()

        // VLC's first samples after a start or an unpause land on the sink at full gain
        // and click; ramping the gain covers them. The fade-in is long enough to overlap
        // the audio sink waking up, the fade-out short enough not to read as a late pause.
        private const val FADE_IN_MS = 260L
        private const val FADE_OUT_MS = 80L
        private const val STUCK_FRAMES = 12

        fun prepareLibraries() {
            createPlayerLock.withLock {
                MediaPlayerFactory(libVlcDiscovery).release()
            }
        }
    }
}

// vlcj only takes "scheme://" as a location; File.toURI()'s "file:/path" is read as a
// relative path and fails to open.
internal fun vlcMrl(url: String): String =
    if (url.startsWith("file:/") && !url.startsWith("file://")) Paths.get(URI(url)).toUri().toString() else url
