package dev.kobbler.kobplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.browser.document
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.coroutines.CoroutineContext

/**
 * Web player implementation using HTML5 <video> element.
 *
 * No VLC — uses browser-native codecs (H.264, VP9, AV1 depending on browser).
 * All state is derived from the <video> element's events.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
internal class WebPlayer(
    parentContext: CoroutineContext,
    private val config: PlayerConfig,
) : Player {

    private val scope = CoroutineScope(
        parentContext + SupervisorJob(parentContext[Job])
    )

    val videoElement: HTMLVideoElement = (document.createElement("video") as HTMLVideoElement).apply {
        style.width = "100%"
        style.height = "100%"
        style.objectFit = "contain"
        autoplay = false
        playsInline = true
    }

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

    private val _positionReported = MutableStateFlow(false)
    override val positionReported: StateFlow<Boolean> = _positionReported.asStateFlow()

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
    override val maxVolume: Float = 1f // browsers cap at 1.0

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

    override val native: NativePlayer get() = NativePlayer.HtmlVideo(videoElement)

    /** Where the stream was asked to open, held until the element can be seeked. */
    private var pendingStartSec: Int? = null

    init {
        // Wire up HTML5 video events to our state machine
        videoElement.addEventListener("loadstart") { _state.value = PlayerState.LOADING; _isBuffering.value = true }
        videoElement.addEventListener("canplay") { _isBuffering.value = false }
        videoElement.addEventListener("playing") { _state.value = PlayerState.PLAYING; _isBuffering.value = false }
        videoElement.addEventListener("pause") { if (_state.value != PlayerState.FINISHED) _state.value = PlayerState.PAUSED }
        videoElement.addEventListener("ended") { _state.value = PlayerState.FINISHED }
        videoElement.addEventListener("waiting") { _isBuffering.value = true; if (_state.value == PlayerState.PLAYING) _state.value = PlayerState.BUFFERING }
        videoElement.addEventListener("error") {
            _error.value = RuntimeException("Video playback error")
            _state.value = PlayerState.ERROR
        }
        videoElement.addEventListener("timeupdate") {
            val time = (videoElement.currentTime * 1000).toLong()
            if (time != _currentMs.value) {
                val resolved = stateAfterClockAdvance(_state.value)
                if (resolved != _state.value) {
                    _state.value = resolved
                    _isBuffering.value = false
                }
            }
            _currentMs.value = time
            _positionReported.value = true
        }
        videoElement.addEventListener("durationchange") {
            val dur = videoElement.duration
            if (!dur.isNaN() && dur.isFinite()) {
                _durationMs.value = (dur * 1000).toLong()
            }
        }
        videoElement.addEventListener("progress") {
            updateBufferPercent()
        }
        videoElement.addEventListener("loadedmetadata") {
            val dur = videoElement.duration
            if (!dur.isNaN() && dur.isFinite()) {
                _durationMs.value = (dur * 1000).toLong()
            }
            // The element has no open-time equivalent of VLC's `start-time`, and a seek
            // before the metadata arrives is dropped. This is the first moment one lands,
            // and it is still ahead of the first frame the viewer sees.
            pendingStartSec?.let { seekTo(it * 1000L) }
            pendingStartSec = null
            reloadTracks()
        }
    }

    // Use addEventListener with string overload for simplicity
    private fun HTMLVideoElement.addEventListener(type: String, handler: (Event) -> Unit) {
        addEventListener(type, handler)
    }

    // --- Commands ---

    override fun load(source: MediaSource) {
        when (source) {
            is MediaSource.Url -> {
                _state.value = PlayerState.LOADING
                _isBuffering.value = true
                _bufferPercent.value = 0f
                _currentMs.value = 0L
                _positionReported.value = false
                _durationMs.value = 0L
                _error.value = null

                pendingStartSec = source.startTimeSec
                videoElement.src = source.url
                videoElement.load()
            }
        }
    }

    override fun play() { videoElement.play() }
    override fun pause() { videoElement.pause() }

    override fun togglePlayPause() {
        if (videoElement.paused) videoElement.play() else videoElement.pause()
    }

    override fun seekTo(ms: Long) {
        val clamped = clampSeekTarget(ms, _durationMs.value)
        videoElement.currentTime = clamped / 1000.0
        _currentMs.value = clamped
    }

    override fun skip(deltaMs: Long) {
        seekTo(_currentMs.value + deltaMs)
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, maxVolume)
        _volume.value = clamped
        videoElement.volume = clamped.toDouble()
    }

    override fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        videoElement.muted = muted
    }

    override fun volumeUp(step: Float) = setVolume(_volume.value + step)
    override fun volumeDown(step: Float) = setVolume(_volume.value - step)

    override fun setPlaybackSpeed(speed: Float) {
        videoElement.playbackRate = speed.toDouble()
        _playbackSpeed.value = speed
    }

    override fun selectAudioTrack(track: TrackInfo?) {
        // HTML5 audioTracks API has limited browser support
        _selectedAudioTrack.value = track
    }

    override fun selectSubtitleTrack(track: TrackInfo?) {
        _selectedSubtitleTrack.value = track
        // HTML5 text track selection is limited in Kotlin/Wasm
        // Consumers can use player.native.asHtmlVideo()?.nativeElement for direct DOM access
    }

    override fun setAspectRatio(ratio: AspectRatio) {
        _aspectRatio.value = ratio
        videoElement.style.objectFit = when (ratio) {
            AspectRatio.FIT -> "contain"
            AspectRatio.STRETCH -> "fill"
            AspectRatio.CROP -> "cover"
        }
    }

    override fun setChapter(chapter: Chapter) {
        seekTo(chapter.offsetMs)
        _currentChapter.value = chapter
    }

    override suspend fun takeScreenshot(path: String) {
        // Not easily possible in browser — would need canvas capture
    }

    private var released = false

    override fun release() {
        if (released) return
        released = true
        videoElement.pause()
        videoElement.src = ""
        videoElement.load()
        videoElement.parentElement?.removeChild(videoElement)
        scope.cancel()
    }

    // --- Internal ---

    private fun updateBufferPercent() {
        val buffered = videoElement.buffered
        if (buffered.length > 0) {
            val end = buffered.end(buffered.length - 1)
            val dur = videoElement.duration
            if (!dur.isNaN() && dur > 0) {
                _bufferPercent.value = (end / dur).toFloat().coerceIn(0f, 1f)
            }
        }
    }

    private fun reloadTracks() {
        // HTML5 text track enumeration is limited in Kotlin/Wasm DOM bindings.
        // Track info will be populated when better Wasm DOM support lands.
        // For now, consumers can access the raw <video> element via player.native.
    }
}
