package dev.kobbler.kobplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.base.Capabilties
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.base.Track
import uk.co.caprica.vlcj.player.base.TrackList
import uk.co.caprica.vlcj.player.base.VideoTrack
import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/**
 * Desktop VLC player implementation using vlcj.
 *
 * All VLC quirks are handled internally:
 * - PLAYING→READY state transitions are absorbed
 * - enableRendering is managed automatically
 * - togglePlayPause bypasses mediamp-style state guards
 * - Seek-while-paused renders extra frames automatically
 */
internal class DesktopPlayer(
    parentContext: CoroutineContext,
    private val config: PlayerConfig,
    vlcLogFile: String? = System.getenv("KOBPLAYER_VLC_LOG"),
) : Player {

    private val scope = CoroutineScope(
        parentContext + SupervisorJob(parentContext[Job])
    ).apply {
        coroutineContext[Job]!!.invokeOnCompletion { release() }
    }

    private val factory = createPlayerLock.withLock {
        MediaPlayerFactory(libVlcDiscovery, config.readAhead.vlcOptions + if (config.verboseLogging) listOf("-vv") else emptyList())
    }

    private val logFile = vlcLogFile?.let { VlcLogFile(factory, it) }

    val vlcPlayer: MediaPlayer = if (config.audioOnly) {
        factory.mediaPlayers().newMediaPlayer()
    } else {
        factory.mediaPlayers().newEmbeddedMediaPlayer()
    }.also {
        // vlcj 5.0.0-M6's release() throws before freeing the player unless its clock
        // was ever watched.
        it.time().startWatching()
    }

    internal val video: VlcVideoSink? = if (!config.audioOnly) {
        VlcVideoSink(
            vlcPlayer,
            onPicture = { layout ->
                pictureLayout = layout
                updateVideoSize()
            },
        )
    } else null

    // --- State flows ---

    private val _state = MutableStateFlow(PlayerState.IDLE)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    override val isPlaying: StateFlow<Boolean> = _state.map { it == PlayerState.PLAYING }
        .distinctUntilChanged()
        .let { flow ->
            MutableStateFlow(false).also { sf ->
                scope.launch { flow.collect { sf.value = it } }
            }
        }

    private val _currentMs = MutableStateFlow(0L)
    override val currentMs: StateFlow<Long> = _currentMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    override val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    override val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _bufferPercent = MutableStateFlow(0f)
    override val bufferPercent: StateFlow<Float> = _bufferPercent.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()
    override val maxVolume: Float = 2f

    private val _isMuted = MutableStateFlow(false)
    override val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    override val audioTracks: StateFlow<List<TrackInfo>> = _audioTracks.asStateFlow()

    private val _selectedAudioTrack = MutableStateFlow<TrackInfo?>(null)
    override val selectedAudioTrack: StateFlow<TrackInfo?> = _selectedAudioTrack.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    override val subtitleTracks: StateFlow<List<TrackInfo>> = _subtitleTracks.asStateFlow()

    private val _selectedSubtitleTrack = MutableStateFlow<TrackInfo?>(null)
    override val selectedSubtitleTrack: StateFlow<TrackInfo?> = _selectedSubtitleTrack.asStateFlow()

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    override val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    private val _currentChapter = MutableStateFlow<Chapter?>(null)
    override val currentChapter: StateFlow<Chapter?> = _currentChapter.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _aspectRatio = MutableStateFlow(AspectRatio.FIT)
    override val aspectRatio: StateFlow<AspectRatio> = _aspectRatio.asStateFlow()

    private val _videoSize = MutableStateFlow(0 to 0)

    // The picture can be negotiated before the track list has its aspect ratio; the size is
    // worked out again when the track catches up.
    @Volatile private var pictureLayout: FrameLayout? = null

    private fun updateVideoSize() {
        val layout = pictureLayout ?: return
        _videoSize.value = withVideoTrack { track ->
            displaySize(layout.width, layout.height, track?.sampleAspectRatio() ?: 0, track?.sampleAspectRatioBase() ?: 0)
        }
    }
    override val videoSize: StateFlow<Pair<Int, Int>> = _videoSize.asStateFlow()

    override val native: NativePlayer get() = NativePlayer.Vlcj(vlcPlayer)

    // --- Internal state ---

    private val setTimeLock = ReentrantLock()
    @Volatile
    private var hasStartedPlaying = false

    // libVLC 4 stops an input asked to pause before it can, and every stream is unpausable
    // while it opens: a pause there threw the open away, resume seek and all. One asked for
    // early is held until the input can take it.
    @Volatile
    private var pausable = false

    @Volatile
    private var pauseWhenPausable = false

    private fun holdIfAsked() {
        if (pausable && (isPreloading.value || pauseWhenPausable)) vlcPlayer.controls().setPause(true)
    }

    // Events from an input we stopped or replaced trail in after it; until the next one
    // opens they describe nothing on screen.
    @Volatile
    private var inputRetired = false
    private var sourceLive = false

    // True while the caller has signalled silent preload. We mute audio output
    // and pause synchronously inside `playing()` so VLC's audio path warms up
    // *before* any sample reaches the speaker — eliminating the cold-start
    // pop/jitter the user hears when audio resumes from the preload window.
    private val isPreloading = MutableStateFlow(false)

    private val gain = GainEnvelope(scope) { vlcPlayer.audio().setVolume(it) }
    internal val gainLevel: StateFlow<Int> get() = gain.level

    private fun userGain() = (_volume.value * 100).roundToInt()

    init {
        if (video != null) {
            scope.launch {
                _state.collect { playerState ->
                    video.renderingEnabled = playerState == PlayerState.PLAYING
                            || playerState == PlayerState.BUFFERING
                            || playerState == PlayerState.LOADING
                }
            }
        }

        scope.launch { watchFrozenClock(_state, _currentMs, _isBuffering, _error, held = { isPreloading.value }) }

        // VLC player events — the heart of our clean state machine
        vlcPlayer.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun opening(mediaPlayer: MediaPlayer) {
                inputRetired = false
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                _durationMs.value = newLength.usToMs()
            }

            override fun trackAdded(mediaPlayer: MediaPlayer, trackType: TrackType, trackId: String) {
                when (trackType) {
                    TrackType.AUDIO -> reloadAudioTracks()
                    TrackType.TEXT -> reloadSubtitleTracks()
                    TrackType.VIDEO -> updateVideoSize()
                    else -> {}
                }
            }

            override fun trackUpdated(mediaPlayer: MediaPlayer, trackType: TrackType, trackId: String) {
                if (trackType == TrackType.VIDEO) updateVideoSize()
            }

            override fun mediaPlayerReady(mediaPlayer: MediaPlayer?) {
                // Absorb VLC's READY state — this is the quirk that breaks mediamp.
                // We just sync volume/mute and load chapters.
                vlcPlayer.submit {
                    gain.reapply()
                    if (_isMuted.value) vlcPlayer.audio().setMute(true)
                }
                loadChapters()
            }

            override fun playing(mediaPlayer: MediaPlayer) {
                if (inputRetired) return
                _isBuffering.value = false
                _bufferPercent.value = 1f
                hasStartedPlaying = true

                if (isPreloading.value || pauseWhenPausable) {
                    // A preload is opened like any other and held as soon as it can be, so
                    // position can't advance silently and audio can't reach the speaker
                    // before unmute. Mirrors AndroidPlayer's Playing handler.
                    holdIfAsked()
                    _state.value = PlayerState.PAUSED
                } else {
                    _state.value = PlayerState.PLAYING
                    gain.rampTo(FADE_IN_MS, ::userGain)
                }

                reloadAudioTracks()
                reloadSubtitleTracks()
            }

            override fun capabilitiesChanged(mediaPlayer: MediaPlayer, oldCapabilities: Capabilties, newCapabilities: Capabilties) {
                if (inputRetired) return
                pausable = newCapabilities.canPause()
                if (hasStartedPlaying) holdIfAsked()
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                if (inputRetired) return
                _state.value = PlayerState.PAUSED
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                if (inputRetired) return
                val next = stateOnStopping(_state.value, requested = false, _currentMs.value, _durationMs.value, sourceLive)
                if (next == PlayerState.ERROR) _error.value = StreamStoppedShort(_currentMs.value, _durationMs.value, sourceLive)
                _state.value = next
            }

            // vlcj fires `stopped` for explicit stop() *and* for media-swap during
            // load(). Treating it as FINISHED triggered the auto-next-episode
            // handler whenever the user switched audio tracks (which loads a new
            // session URL). stop()/load() set state explicitly; leave it alone.

            override fun error(mediaPlayer: MediaPlayer) {
                _error.value = RuntimeException("VLC playback error")
                _state.value = PlayerState.ERROR
            }

            override fun bufferingChanged(mediaPlayer: MediaPlayer, newCache: Float) {
                if (inputRetired) return
                // A fraction in libVLC 4, where 3 reported a percentage.
                val filled = newCache.coerceIn(0f, 1f)
                _bufferPercent.value = filled
                if (!hasStartedPlaying) return
                _isBuffering.value = filled < 1f
                _state.value = stateOnBuffering(_state.value, full = filled >= 1f)
            }

            override fun positionChanged(mediaPlayer: MediaPlayer, newTimeUs: Long, newPosition: Double) {
                if (inputRetired) return
                val newTime = newTimeUs.usToMs()
                if (newTime != _currentMs.value) {
                    val resolved = stateAfterClockAdvance(_state.value, isPreloading.value)
                    if (resolved != _state.value) {
                        _state.value = resolved
                        _isBuffering.value = false
                    }
                }
                _currentMs.value = newTime
            }
        })
    }

    // --- Commands ---

    private var spuDisabled = false
    private var loadedSource: MediaSource.Url? = null

    @Volatile
    private var decoding: VideoDecoding = if (config.hardwareAcceleration) VideoDecoding.Auto else VideoDecoding.Software

    override fun setVideoDecoding(decoding: VideoDecoding) {
        this.decoding = decoding
    }

    internal var gpuDecodeRequested = false
        private set

    override fun load(source: MediaSource) {
        when (source) {
            is MediaSource.Url -> {
                inputRetired = true
                sourceLive = source.live
                _state.value = PlayerState.LOADING
                _isBuffering.value = true
                _bufferPercent.value = 0f
                _currentMs.value = source.startTimeSec?.let { it * 1000L } ?: 0L
                _durationMs.value = 0L
                _error.value = null
                hasStartedPlaying = false
                pausable = false
                pauseWhenPausable = false
                loadedSource = source
                val device = decoderDevice(source, decoding)
                gpuDecodeRequested = device.name != "none"
                gain.set(0)

                val options = vlcMediaOptions(source, config).toTypedArray()
                vlcPlayer.submit {
                    stopCurrentInput()
                    vlcPlayer.setDecoderDevice(device)
                    vlcPlayer.media().play(vlcMrl(source.url), *options)
                    // Not :start-time: libVLC 4 makes that the title's start, and reports times
                    // and length from it. A seek before play() has no input to land on.
                    source.startTimeSec?.let { vlcPlayer.controls().setTime((it * 1000L).msToUs(), true) }
                    // Re-apply mute after play — new media may reset audio state.
                    // This prevents audio leaking during muted preload on detail pages.
                    if (_isMuted.value) vlcPlayer.audio().setMute(true)
                }
            }
        }
    }

    // Media handed to a libVLC 4 player that is still playing is swapped in gaplessly: the
    // player never leaves its playing state, so the new title reports no opening, playing,
    // pause or end, and nothing marks where the old input's events stop.
    private fun stopCurrentInput() {
        if (vlcPlayer.status().state() in inputGone) return
        vlcPlayer.controls().stop()
        val deadline = System.nanoTime() + STOP_WAIT_NS
        while (vlcPlayer.status().state() !in inputGone && System.nanoTime() < deadline) Thread.sleep(10)
    }

    override fun play() {
        pauseWhenPausable = false
        if (_state.value == PlayerState.PLAYING) gain.rampTo(FADE_IN_MS, ::userGain)
        vlcPlayer.submit { vlcPlayer.controls().play() }
    }

    override fun pause() {
        if (!pausable) {
            pauseWhenPausable = true
            return
        }
        // setPause(true), not pause(): that one toggles, and a second call unpauses.
        val pauseNow = { vlcPlayer.submit { vlcPlayer.controls().setPause(true) } }
        if (_state.value == PlayerState.PLAYING) gain.rampTo(FADE_OUT_MS, { 0 }, pauseNow)
        else pauseNow()
    }

    override fun stop() {
        inputRetired = true
        try {
            vlcPlayer.submit { vlcPlayer.controls().stop() }
        } catch (_: RejectedExecutionException) {}
        _state.value = PlayerState.IDLE
        _currentMs.value = 0L
        _durationMs.value = 0L
        _isBuffering.value = false
        _bufferPercent.value = 0f
        hasStartedPlaying = false
        pausable = false
        pauseWhenPausable = false
        video?.clear()
    }

    override fun togglePlayPause() {
        // Bypass any state guards — use vlcj controls directly
        when (playPauseIntent(_state.value)) {
            PlayPauseIntent.Play -> play()
            PlayPauseIntent.Pause -> pause()
            PlayPauseIntent.None -> {}
        }
    }

    override fun seekTo(ms: Long) {
        val clamped = clampSeekTarget(ms, _durationMs.value)
        if (clamped == _currentMs.value) return

        _currentMs.value = clamped
        vlcPlayer.submit {
            setTimeLock.withLock {
                vlcPlayer.controls().setTime(clamped.msToUs())
            }
        }
        video?.allowFrames(2)
    }

    override fun skip(deltaMs: Long) {
        if (_state.value == PlayerState.PAUSED) {
            // While paused, positionChanged won't fire, so update manually
            _currentMs.value = (_currentMs.value + deltaMs)
                .coerceIn(0, _durationMs.value.coerceAtLeast(0))
        }
        vlcPlayer.submit {
            setTimeLock.withLock {
                vlcPlayer.controls().skipTime(deltaMs.msToUs())
            }
        }
        video?.allowFrames(2)
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, maxVolume)
        _volume.value = clamped
        // A ramp reads the volume on every step, and a paused player sits at zero until
        // the next fade-in, so only a steady playing gain is set directly.
        val audible = _state.value == PlayerState.PLAYING || _state.value == PlayerState.BUFFERING
        if (audible && !isPreloading.value && !gain.isRamping) gain.set(userGain())
    }

    override fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        vlcPlayer.audio().setMute(muted)
    }

    override fun setPreloading(preloading: Boolean) {
        if (isPreloading.value == preloading) return
        isPreloading.value = preloading
        if (preloading) {
            // Re-applied in mediaPlayerReady because the initial setVolume can
            // lose to VLC's own readiness reset.
            gain.set(0)
        } else {
            gain.rampTo(FADE_IN_MS, ::userGain)
        }
    }

    override fun volumeUp(step: Float) = setVolume(_volume.value + step)
    override fun volumeDown(step: Float) = setVolume(_volume.value - step)

    override fun setPlaybackSpeed(speed: Float) {
        vlcPlayer.submit { vlcPlayer.controls().setRate(speed) }
        _playbackSpeed.value = speed
    }

    override fun selectAudioTrack(track: TrackInfo?) {
        if (track == null) {
            vlcPlayer.tracks().deselect(TrackType.AUDIO)
        } else if (_audioTracks.value.any { it.id == track.id }) {
            vlcPlayer.tracks().select(TrackType.AUDIO, track.id)
            _selectedAudioTrack.value = track
        }
    }

    override fun selectSubtitleTrack(track: TrackInfo?) {
        if (track == null) {
            spuDisabled = true
            vlcPlayer.tracks().deselect(TrackType.TEXT)
            _selectedSubtitleTrack.value = null
        } else {
            if (_subtitleTracks.value.none { it.id == track.id }) return
            spuDisabled = false
            val source = loadedSource
            val ordinal = _subtitleTracks.value.indexOf(track)
            if (gpuDecodeRequested && source != null && ordinal >= 0) {
                // VLC can only draw subtitles into a picture the CPU decoded — reopen there.
                load(source.copy(subtitleOrdinal = ordinal, subtitlesOff = false, startTimeSec = (_currentMs.value / 1000).toInt()))
            } else {
                vlcPlayer.tracks().select(TrackType.TEXT, track.id)
            }
            _selectedSubtitleTrack.value = track
        }
    }

    override fun setAspectRatio(ratio: AspectRatio) {
        _aspectRatio.value = ratio
    }

    override fun setChapter(chapter: Chapter) {
        val idx = _chapters.value.indexOf(chapter)
        if (idx >= 0) {
            vlcPlayer.chapters().setChapter(idx)
            _currentChapter.value = chapter
        }
    }

    override suspend fun takeScreenshot(path: String) {
        vlcPlayer.submit {
            vlcPlayer.snapshots().save(File(path))
        }
    }

    private var released = false

    override fun release() {
        if (released) return
        released = true
        _currentMs.value = 0L
        try {
            vlcPlayer.submit { vlcPlayer.controls().stop() }
        } catch (_: RejectedExecutionException) {
        }
        video?.clear()
        // Detached on purpose: release() has to outlive `scope`, which is cancelled
        // right after. NonCancellable inside `scope` did this by breaking structured
        // concurrency, which is now deprecated.
        CoroutineScope(Dispatchers.IO).launch {
            vlcPlayer.release()
            logFile?.close()
        }
        scope.cancel()
    }

    // --- Internal helpers ---

    private fun <R> withVideoTrack(read: (VideoTrack?) -> R): R {
        val selected = vlcPlayer.tracks().selectedVideoTrack()
        return if (selected != null) selected.use(read) else vlcPlayer.tracks().videoTracks().use { read(it.tracks().firstOrNull()) }
    }

    private fun reloadAudioTracks() {
        val tracks = trackInfos(vlcPlayer.tracks().audioTracks())
        _audioTracks.value = tracks
        val current = vlcPlayer.tracks().selectedAudioTrack()?.use { it.trackId() }
        _selectedAudioTrack.value = tracks.find { it.id == current }
    }

    private fun reloadSubtitleTracks() {
        val tracks = trackInfos(vlcPlayer.tracks().textTracks())
        _subtitleTracks.value = tracks

        // Same race as Android: VLC re-selects a default SPU once the streams are parsed,
        // after the app has asked for none.
        val selected = vlcPlayer.tracks().selectedTextTrack()?.use { it.trackId() }
        if (spuDisabled && selected != null) vlcPlayer.tracks().deselect(TrackType.TEXT)
        val current = if (spuDisabled) null else selected
        _selectedSubtitleTrack.value = tracks.find { it.id == current }
    }

    private fun loadChapters() {
        _chapters.value = vlcPlayer.chapters().allDescriptions().flatMap { title ->
            title.map {
                Chapter(
                    name = it.name(),
                    offsetMs = it.offset().usToMs(),
                    durationMs = it.duration().usToMs(),
                )
            }
        }
    }

    companion object {
        private val createPlayerLock = ReentrantLock()

        // VLC's first samples after a start or an unpause land on the sink at full gain
        // and click; ramping the gain covers them. The fade-in is long enough to overlap
        // the audio sink waking up, the fade-out short enough not to read as a late pause.
        private const val FADE_IN_MS = 260L
        private const val FADE_OUT_MS = 80L
        private const val STOP_WAIT_NS = 5_000_000_000L
        private val inputGone = setOf(State.NOTHING_SPECIAL, State.STOPPED, State.ERROR)

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

internal fun vlcMediaOptions(source: MediaSource.Url, config: PlayerConfig): List<String> {
    val headers = source.headers.mapKeys { it.key.lowercase() }
    return buildList {
        add("http-user-agent=${headers["user-agent"] ?: config.httpUserAgent}")
        add("network-caching=${source.networkCacheMs ?: config.networkCacheMs}")
        // libVLC 4 seeks a Matroska stream only to cue points it has verified, and it
        // verifies none over a network: every seek decoded the title from its first frame.
        add("demux=mkv_trusted,any")
        if (config.audioOnly) add("no-video")
        headers["referer"]?.let { add("http-referrer=$it") }
        source.audioLanguage?.let { add("audio-language=$it") }
        if (source.subtitlesOff) add("sub-language=none")
        else source.subtitleOrdinal?.let { add("sub-track=$it") }
    }
}

private fun <T : Track> trackInfos(list: TrackList<T>): List<TrackInfo> = list.use {
    it.tracks().mapIndexed { index, track -> TrackInfo(track.trackId(), track.name() ?: "Track ${index + 1}") }
}

private inline fun <L : TrackList<*>, R> L.use(block: (L) -> R): R = try { block(this) } finally { release() }

private inline fun <T : Track, R> T.use(block: (T) -> R): R = try { block(this) } finally { release() }

// libvlc 4 counts time in microseconds, and vlcj 5 passes it through unconverted.
private fun Long.usToMs() = this / 1000
private fun Long.msToUs() = this * 1000

// ReentrantLock.withLock with contract
private inline fun <T> ReentrantLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
