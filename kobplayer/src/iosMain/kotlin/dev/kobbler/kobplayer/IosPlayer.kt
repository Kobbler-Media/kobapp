package dev.kobbler.kobplayer

import kotlinx.coroutines.CoroutineScope
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
import platform.Foundation.NSURL
import platform.VLCKit.VLCMedia
import platform.VLCKit.VLCMediaPlayer
import platform.VLCKit.VLCMediaPlayerDelegateProtocol
import platform.VLCKit.VLCMediaPlayerState
import platform.darwin.NSObject
import kotlin.coroutines.CoroutineContext

/**
 * iOS VLC player implementation using MobileVLCKit 3.3.15 via Kotlin/Native ObjC interop.
 *
 * Requires MobileVLCKit CocoaPod in the iOS app's Podfile:
 * ```ruby
 * pod 'MobileVLCKit', '~> 3.3.15'
 * ```
 *
 * Build note: This file requires macOS with Xcode to compile.
 * On Linux, replace with the stub version (IosPlayer.stub.kt) for CI builds.
 */
internal class IosPlayer(
    parentContext: CoroutineContext,
    private val config: PlayerConfig,
) : Player {

    private val scope = CoroutineScope(
        parentContext + SupervisorJob(parentContext[Job])
    )

    val vlcMediaPlayer = VLCMediaPlayer()

    // --- State flows ---

    private val _state = MutableStateFlow(PlayerState.IDLE)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false).also { sf ->
        scope.launch {
            _state.map { it == PlayerState.PLAYING }.distinctUntilChanged().collect { sf.value = it }
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

    override val native: NativePlayer get() = NativePlayer.VlcKit(vlcMediaPlayer)

    // --- Delegate for VLC state change notifications ---

    private val delegate = object : NSObject(), VLCMediaPlayerDelegateProtocol {
        override fun mediaPlayerStateChanged(aNotification: platform.Foundation.NSNotification) {
            when (vlcMediaPlayer.state) {
                VLCMediaPlayerState.VLCMediaPlayerStateOpening -> {
                    _state.value = PlayerState.LOADING
                    _isBuffering.value = true
                }
                VLCMediaPlayerState.VLCMediaPlayerStateBuffering -> {
                    _isBuffering.value = true
                    if (_state.value == PlayerState.PLAYING) {
                        _state.value = PlayerState.BUFFERING
                    }
                }
                VLCMediaPlayerState.VLCMediaPlayerStatePlaying -> {
                    _state.value = PlayerState.PLAYING
                    _isBuffering.value = false
                    _bufferPercent.value = 1f
                    reloadTracks()
                }
                VLCMediaPlayerState.VLCMediaPlayerStatePaused -> {
                    _state.value = PlayerState.PAUSED
                }
                VLCMediaPlayerState.VLCMediaPlayerStateStopped -> {
                    // Stopped fires for explicit stop() *and* for media-swap during
                    // load(). Don't emit FINISHED — that drives auto-next-episode
                    // and would fire whenever the user switched audio tracks.
                }
                VLCMediaPlayerState.VLCMediaPlayerStateEnded -> {
                    _state.value = PlayerState.FINISHED
                }
                VLCMediaPlayerState.VLCMediaPlayerStateError -> {
                    _error.value = RuntimeException("VLC playback error")
                    _state.value = PlayerState.ERROR
                }
                else -> {}
            }
        }

        override fun mediaPlayerTimeChanged(aNotification: platform.Foundation.NSNotification) {
            val time = vlcMediaPlayer.time.longValue
            if (time != _currentMs.value) {
                val resolved = stateAfterClockAdvance(_state.value)
                if (resolved != _state.value) {
                    _state.value = resolved
                    _isBuffering.value = false
                }
            }
            _currentMs.value = time
            vlcMediaPlayer.media?.length?.longValue?.let { dur ->
                if (dur > 0) _durationMs.value = dur
            }
        }
    }

    init {
        vlcMediaPlayer.delegate = delegate

        // Fallback buffering detection
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
    }

    // --- Commands ---

    override fun load(source: MediaSource) {
        when (source) {
            is MediaSource.Url -> {
                _state.value = PlayerState.LOADING
                _isBuffering.value = true
                _bufferPercent.value = 0f
                _currentMs.value = 0L
                _durationMs.value = 0L
                _error.value = null

                val url = NSURL.URLWithString(source.url) ?: return
                val media = VLCMedia(uRL = url)
                val headers = source.headers.mapKeys { it.key.lowercase() }
                media.addOption(":http-user-agent=${headers["user-agent"] ?: config.httpUserAgent}")
                media.addOption(":network-caching=${source.networkCacheMs ?: config.networkCacheMs}")
                headers["referer"]?.let { media.addOption(":http-referrer=$it") }
                source.startTimeSec?.let { media.addOption(":start-time=$it") }

                vlcMediaPlayer.media = media
                vlcMediaPlayer.play()
            }
        }
    }

    override fun play() { vlcMediaPlayer.play() }
    override fun pause() { vlcMediaPlayer.pause() }

    override fun togglePlayPause() {
        when (playPauseIntent(_state.value)) {
            PlayPauseIntent.Play -> vlcMediaPlayer.play()
            PlayPauseIntent.Pause -> vlcMediaPlayer.pause()
            PlayPauseIntent.None -> {}
        }
    }

    override fun seekTo(ms: Long) {
        val clamped = clampSeekTarget(ms, _durationMs.value)
        _currentMs.value = clamped
        vlcMediaPlayer.time = clamped.toInt()
    }

    override fun skip(deltaMs: Long) {
        seekTo(_currentMs.value + deltaMs)
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, maxVolume)
        _volume.value = clamped
        vlcMediaPlayer.audio?.volume = (clamped * 100).toInt()
    }

    override fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        if (muted) {
            vlcMediaPlayer.audio?.volume = 0
        } else {
            vlcMediaPlayer.audio?.volume = (_volume.value * 100).toInt()
        }
    }

    override fun volumeUp(step: Float) = setVolume(_volume.value + step)
    override fun volumeDown(step: Float) = setVolume(_volume.value - step)

    override fun setPlaybackSpeed(speed: Float) {
        vlcMediaPlayer.rate = speed
        _playbackSpeed.value = speed
    }

    override fun selectAudioTrack(track: TrackInfo?) {
        val idx = track?.id?.toIntOrNull() ?: -1
        vlcMediaPlayer.currentAudioTrackIndex = idx
        _selectedAudioTrack.value = track
    }

    override fun selectSubtitleTrack(track: TrackInfo?) {
        val idx = track?.id?.toIntOrNull() ?: -1
        vlcMediaPlayer.currentVideoSubTitleIndex = idx
        _selectedSubtitleTrack.value = track
    }

    override fun setAspectRatio(ratio: AspectRatio) {
        _aspectRatio.value = ratio
        // VLCKit aspect ratio is handled by the drawable UIView scaling
    }

    override fun setChapter(chapter: Chapter) {
        val idx = _chapters.value.indexOf(chapter)
        if (idx >= 0) {
            vlcMediaPlayer.currentChapterIndex = idx
            _currentChapter.value = chapter
        }
    }

    override suspend fun takeScreenshot(path: String) {
        // MobileVLCKit doesn't have a direct screenshot API
    }

    private var released = false

    override fun release() {
        if (released) return
        released = true
        vlcMediaPlayer.stop()
        vlcMediaPlayer.media = null
        scope.cancel()
    }

    // --- Internal ---

    @Suppress("UNCHECKED_CAST")
    private fun reloadTracks() {
        // Audio tracks
        val audioNames = (vlcMediaPlayer.audioTrackNames as? List<String>) ?: emptyList()
        val audioIds = (vlcMediaPlayer.audioTrackIndexes as? List<Int>) ?: emptyList()
        _audioTracks.value = audioNames.zip(audioIds)
            .filter { it.second != -1 }
            .map { (name, id) -> TrackInfo(id.toString(), name) }
        _selectedAudioTrack.value = _audioTracks.value.find {
            it.id == vlcMediaPlayer.currentAudioTrackIndex.toString()
        }

        // Subtitle tracks
        val subNames = (vlcMediaPlayer.videoSubTitlesNames as? List<String>) ?: emptyList()
        val subIds = (vlcMediaPlayer.videoSubTitlesIndexes as? List<Int>) ?: emptyList()
        _subtitleTracks.value = subNames.zip(subIds)
            .filter { it.second != -1 }
            .map { (name, id) -> TrackInfo(id.toString(), name) }
        _selectedSubtitleTrack.value = _subtitleTracks.value.find {
            it.id == vlcMediaPlayer.currentVideoSubTitleIndex.toString()
        }
    }
}
