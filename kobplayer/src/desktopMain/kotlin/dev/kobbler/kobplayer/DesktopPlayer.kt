package dev.kobbler.kobplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.Media
import uk.co.caprica.vlcj.media.MediaEventAdapter
import uk.co.caprica.vlcj.media.MediaParsedStatus
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.media.VideoTrackInfo
import uk.co.caprica.vlcj.binding.lib.LibC
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
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
) : Player {

    private val scope = CoroutineScope(
        parentContext + SupervisorJob(parentContext[Job])
    ).apply {
        coroutineContext[Job]!!.invokeOnCompletion { release() }
    }

    private val factory = createPlayerLock.withLock {
        pluginSearchPathReady
        MediaPlayerFactory(if (config.verboseLogging) listOf("-vv") else emptyList())
    }

    val vlcPlayer: MediaPlayer = if (config.audioOnly) {
        factory.mediaPlayers().newMediaPlayer()
    } else {
        factory.mediaPlayers().newEmbeddedMediaPlayer()
    }

    internal val video: VlcVideoSink? = if (!config.audioOnly) {
        VlcVideoSink(
            vlcPlayer,
            visibleSize = { currentVideoTrack()?.let { it.width() to it.height() } },
            onPicture = { layout ->
                val track = currentVideoTrack()
                _videoSize.value = displaySize(
                    layout.width,
                    layout.height,
                    track?.sampleAspectRatio() ?: 0,
                    track?.sampleAspectRatioBase() ?: 0,
                )
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

    override val progress: StateFlow<Float> = MutableStateFlow(0f).also { sf ->
        scope.launch {
            _currentMs.collect { pos ->
                val dur = _durationMs.value
                sf.value = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            }
        }
    }

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
    override val videoSize: StateFlow<Pair<Int, Int>> = _videoSize.asStateFlow()

    override val native: NativePlayer get() = NativePlayer.Vlcj(vlcPlayer)

    // --- Internal state ---

    private val setTimeLock = ReentrantLock()
    private var hasStartedPlaying = false

    // True while the caller has signalled silent preload. We mute audio output
    // and pause synchronously inside `playing()` so VLC's audio path warms up
    // *before* any sample reaches the speaker — eliminating the cold-start
    // pop/jitter the user hears when audio resumes from the preload window.
    private val isPreloading = MutableStateFlow(false)
    private var fadeJob: Job? = null

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

        // Fallback buffering detection: position not advancing for 1.5s while "playing"
        scope.launch {
            var lastPosition = 0L
            while (true) {
                delay(1500)
                if (_state.value == PlayerState.PLAYING) {
                    val pos = _currentMs.value
                    if (pos == lastPosition && pos > 0) {
                        _isBuffering.value = true
                    }
                    lastPosition = pos
                }
            }
        }

        // VLC media events
        vlcPlayer.events().addMediaEventListener(object : MediaEventAdapter() {
            override fun mediaParsedChanged(media: Media, newStatus: MediaParsedStatus) {
                if (newStatus == MediaParsedStatus.DONE) {
                    updateMediaProperties()
                }
            }
        })

        // VLC player events — the heart of our clean state machine
        vlcPlayer.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                _durationMs.value = newLength
            }

            override fun elementaryStreamAdded(mediaPlayer: MediaPlayer?, type: TrackType?, id: Int) {
                when (type) {
                    TrackType.AUDIO -> reloadAudioTracks()
                    TrackType.TEXT -> reloadSubtitleTracks()
                    else -> {}
                }
            }

            override fun mediaPlayerReady(mediaPlayer: MediaPlayer?) {
                // Absorb VLC's READY state — this is the quirk that breaks mediamp.
                // We just sync volume/mute and load chapters.
                vlcPlayer.submit {
                    val vol = if (isPreloading.value) 0 else (_volume.value * 100).roundToInt()
                    vlcPlayer.audio().setVolume(vol)
                    if (_isMuted.value) vlcPlayer.audio().mute()
                }
                loadChapters()
            }

            override fun playing(mediaPlayer: MediaPlayer) {
                _isBuffering.value = false
                _bufferPercent.value = 1f
                hasStartedPlaying = true

                if (isPreloading.value) {
                    // Caller-side pause() races VLC's auto-start; intercept here so
                    // position can't advance silently and audio can't reach the
                    // speaker before unmute. Mirrors AndroidPlayer's Playing handler.
                    vlcPlayer.controls().setPause(true)
                    _state.value = PlayerState.PAUSED
                } else {
                    _state.value = PlayerState.PLAYING
                }

                // Parse media and reload tracks on play
                vlcPlayer.submit { vlcPlayer.media().parsing().parse() }
                reloadAudioTracks()
                reloadSubtitleTracks()
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                _state.value = PlayerState.PAUSED
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                _state.value = PlayerState.FINISHED
            }

            // vlcj fires `stopped` for explicit stop() *and* for media-swap during
            // load(). Treating it as FINISHED triggered the auto-next-episode
            // handler whenever the user switched audio tracks (which loads a new
            // session URL). stop()/load() set state explicitly; leave it alone.

            override fun error(mediaPlayer: MediaPlayer) {
                _error.value = RuntimeException("VLC playback error")
                _state.value = PlayerState.ERROR
            }

            override fun buffering(mediaPlayer: MediaPlayer?, newCache: Float) {
                _bufferPercent.value = (newCache / 100f).coerceIn(0f, 1f)
                if (newCache < 100f && hasStartedPlaying) {
                    _state.value = PlayerState.BUFFERING
                    _isBuffering.value = true
                } else if (newCache >= 100f && _state.value == PlayerState.BUFFERING) {
                    _state.value = PlayerState.PLAYING
                    _isBuffering.value = false
                }
            }

            // Use timeChanged (direct ms) instead of positionChanged (fraction × duration).
            // The fraction path inherits libvlc's small position rounding and any
            // duration estimate error, which subtitles read as desync. Android already
            // uses TimeChanged for the same reason.
            override fun timeChanged(mediaPlayer: MediaPlayer?, newTime: Long) {
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
                _state.value = PlayerState.LOADING
                _isBuffering.value = true
                _bufferPercent.value = 0f
                _currentMs.value = 0L
                _durationMs.value = 0L
                _error.value = null
                hasStartedPlaying = false
                loadedSource = source
                gpuDecodeRequested = decodesOnGpu(source, decoding)

                vlcPlayer.media().play(vlcMrl(source.url), *vlcMediaOptions(source, config, decoding).toTypedArray())
                // Re-apply mute after play — new media may reset audio state.
                // This prevents audio leaking during muted preload on detail pages.
                if (_isMuted.value && !vlcPlayer.audio().isMute) {
                    vlcPlayer.audio().mute()
                }
            }
        }
    }

    override fun play() {
        vlcPlayer.submit { vlcPlayer.controls().play() }
    }

    override fun pause() {
        // Use setPause(true) — not pause() which is a toggle and can unpause if called twice
        vlcPlayer.submit { vlcPlayer.controls().setPause(true) }
    }

    override fun stop() {
        try {
            vlcPlayer.submit { vlcPlayer.controls().stop() }
        } catch (_: RejectedExecutionException) {}
        _state.value = PlayerState.IDLE
        _currentMs.value = 0L
        _durationMs.value = 0L
        _isBuffering.value = false
        _bufferPercent.value = 0f
        hasStartedPlaying = false
        video?.clear()
    }

    override fun togglePlayPause() {
        // Bypass any state guards — use vlcj controls directly
        when (playPauseIntent(_state.value)) {
            PlayPauseIntent.Play -> vlcPlayer.submit { vlcPlayer.controls().play() }
            PlayPauseIntent.Pause -> vlcPlayer.submit { vlcPlayer.controls().setPause(true) }
            PlayPauseIntent.None -> {}
        }
    }

    override fun seekTo(ms: Long) {
        val clamped = clampSeekTarget(ms, _durationMs.value)
        if (clamped == _currentMs.value) return

        _currentMs.value = clamped
        vlcPlayer.submit {
            setTimeLock.withLock {
                vlcPlayer.controls().setTime(clamped)
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
                vlcPlayer.controls().skipTime(deltaMs)
            }
        }
        video?.allowFrames(2)
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, maxVolume)
        _volume.value = clamped
        vlcPlayer.audio().setVolume((clamped * 100).roundToInt())
    }

    override fun setMuted(muted: Boolean) {
        if (vlcPlayer.audio().isMute == muted) return
        _isMuted.value = muted
        vlcPlayer.audio().mute()
    }

    override fun setPreloading(preloading: Boolean) {
        if (isPreloading.value == preloading) return
        isPreloading.value = preloading
        fadeJob?.cancel()
        if (preloading) {
            // Re-applied in mediaPlayerReady because the initial setVolume can
            // lose to VLC's own readiness reset.
            vlcPlayer.audio().setVolume(0)
        } else {
            // Ramp 0 → user volume over ~250ms instead of jumping. VLC's first
            // sample on resume-from-pause carries a click; fading the gain
            // covers it. Long enough to overlap likely audio-sink wake-up,
            // short enough to be imperceptible as a fade.
            val target = (_volume.value * 100).roundToInt()
            fadeJob = scope.launch {
                val steps = 12
                val stepMs = 22L
                for (i in 1..steps) {
                    vlcPlayer.audio().setVolume((target.toLong() * i / steps).toInt())
                    delay(stepMs)
                }
            }
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
            vlcPlayer.audio().setTrack(-1)
        } else {
            val id = track.id.toIntOrNull() ?: return
            val validIds = vlcPlayer.audio().trackDescriptions().map { it.id() }
            if (id in validIds) {
                vlcPlayer.audio().setTrack(id)
                _selectedAudioTrack.value = track
            }
        }
    }

    override fun selectSubtitleTrack(track: TrackInfo?) {
        if (track == null) {
            spuDisabled = true
            vlcPlayer.subpictures().setTrack(-1)
            _selectedSubtitleTrack.value = null
        } else {
            val id = track.id.toIntOrNull() ?: return
            spuDisabled = false
            val validIds = vlcPlayer.subpictures().trackDescriptions().map { it.id() }
            if (id !in validIds) return
            val source = loadedSource
            val ordinal = _subtitleTracks.value.indexOf(track)
            if (gpuDecodeRequested && source != null && ordinal >= 0) {
                // VLC can only draw subtitles into a picture the CPU decoded — reopen there.
                load(source.copy(subtitleOrdinal = ordinal, subtitlesOff = false, startTimeSec = (_currentMs.value / 1000).toInt()))
            } else {
                vlcPlayer.subpictures().setTrack(id)
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
        CoroutineScope(Dispatchers.IO).launch { vlcPlayer.release() }
        scope.cancel()
    }

    // --- Internal helpers ---

    private fun currentVideoTrack(): VideoTrackInfo? {
        val tracks = vlcPlayer.media().info()?.videoTracks().orEmpty()
        val selected = vlcPlayer.video().track()
        return tracks.firstOrNull { it.id() == selected } ?: tracks.firstOrNull()
    }

    private fun updateMediaProperties() {
        val info = vlcPlayer.media().info() ?: return
        _durationMs.value = info.duration()
    }

    private fun reloadAudioTracks() {
        val tracks = vlcPlayer.audio().trackDescriptions()
            .filter { it.id() != -1 }
            .map { TrackInfo(it.id().toString(), it.description()) }
        _audioTracks.value = tracks

        // Update selected track
        val currentId = vlcPlayer.audio().track()
        _selectedAudioTrack.value = tracks.find { it.id == currentId.toString() }
    }

    private fun reloadSubtitleTracks() {
        val tracks = vlcPlayer.subpictures().trackDescriptions()
            .filter { it.id() != -1 }
            .map { TrackInfo(it.id().toString(), it.description()) }
        _subtitleTracks.value = tracks

        // Same race as Android: VLC re-selects a default SPU once the streams are parsed,
        // after the app has asked for none.
        if (spuDisabled && vlcPlayer.subpictures().track() != -1) vlcPlayer.subpictures().setTrack(-1)
        val currentId = if (spuDisabled) -1 else vlcPlayer.subpictures().track()
        _selectedSubtitleTrack.value = tracks.find { it.id == currentId.toString() }
    }

    private fun loadChapters() {
        _chapters.value = vlcPlayer.chapters().allDescriptions().flatMap { title ->
            title.map {
                Chapter(
                    name = it.name(),
                    offsetMs = it.offset(),
                    durationMs = it.duration(),
                )
            }
        }
    }

    companion object {
        private val createPlayerLock = ReentrantLock()

        // libvlc reads VLC_PLUGIN_PATH once, when the first instance is created, so this
        // has to run before any MediaPlayerFactory exists.
        private val pluginSearchPathReady: Unit by lazy {
            NativeDiscovery().discover()
            bundledPluginDir()?.let { dir ->
                val existing = System.getenv("VLC_PLUGIN_PATH")
                LibC.INSTANCE.setenv("VLC_PLUGIN_PATH", listOfNotNull(existing, dir.path).joinToString(File.pathSeparator), 1)
            }
            Unit
        }

        private fun bundledPluginDir(): File? =
            listOfNotNull(System.getProperty("kobplayer.vlc.plugins"), System.getProperty("compose.application.resources.dir")?.let { "$it/vlc-plugins" })
                .map(::File)
                .firstOrNull { it.isDirectory }

        fun prepareLibraries() {
            createPlayerLock.withLock {
                pluginSearchPathReady
                CallbackMediaPlayerComponent().release()
            }
        }
    }
}

// vlcj only takes "scheme://" as a location; File.toURI()'s "file:/path" is read as a
// relative path and fails to open.
internal fun vlcMrl(url: String): String =
    if (url.startsWith("file:/") && !url.startsWith("file://")) Paths.get(URI(url)).toUri().toString() else url

// VLC 3 blends subpictures onto the decoder's own picture, and a hardware decoder's picture
// is an opaque GPU surface: the blend fails and the subtitles silently vanish. So the GPU
// only decodes when VLC has no subtitles to draw — the app's own overlay is unaffected.
internal fun decodesOnGpu(source: MediaSource.Url, decoding: VideoDecoding): Boolean =
    decoding != VideoDecoding.Software && source.subtitleOrdinal == null

internal fun vlcMediaOptions(source: MediaSource.Url, config: PlayerConfig, decoding: VideoDecoding): List<String> {
    val headers = source.headers.mapKeys { it.key.lowercase() }
    return buildList {
        add("http-user-agent=${headers["user-agent"] ?: config.httpUserAgent}")
        add("network-caching=${source.networkCacheMs ?: config.networkCacheMs}")
        headers["referer"]?.let { add("http-referrer=$it") }
        source.audioLanguage?.let { add("audio-language=$it") }
        if (source.subtitlesOff) add("sub-language=none")
        else source.subtitleOrdinal?.let { add("sub-track=$it") }
        source.startTimeSec?.let { add("start-time=$it") }
        // libvlc forces avcodec-hw=none on any player with video callbacks; a media option
        // outranks it. kobvaapi is the bundled VA-API decoder (native/kobvaapi) — the one
        // VLC 3.0.23 ships is dead — and "any" still reaches VDPAU, DXVA and VideoToolbox.
        if (decodesOnGpu(source, decoding)) {
            add("avcodec-hw=kobvaapi,any")
            (decoding as? VideoDecoding.Gpu)?.let { add("kobvaapi-device=${it.device}") }
        }
    }
}

// ReentrantLock.withLock with contract
private inline fun <T> ReentrantLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
