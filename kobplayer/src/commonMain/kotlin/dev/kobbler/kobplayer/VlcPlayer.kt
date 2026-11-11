package dev.kobbler.kobplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

// A remux opens with a track event per stream, dozens in one burst: one refresh answers them all.
private const val TrackRefreshSettleMs = 50L

/**
 * What a libVLC 4 player does with its [VlcBinding], the same on every platform: the state
 * machine its events drive, the hold for a pause asked for before the input can take it,
 * picking a title back up on a fresh player, seeks, tracks and chapters.
 */
internal abstract class VlcPlayer<B : VlcBinding>(
    parentContext: CoroutineContext,
    protected val config: PlayerConfig,
) : Player {

    protected val scope = CoroutineScope(parentContext + SupervisorJob(parentContext[Job]))

    protected val _state = MutableStateFlow(PlayerState.IDLE)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false).also { sf ->
        scope.launch { _state.map { it == PlayerState.PLAYING }.distinctUntilChanged().collect { sf.value = it } }
    }

    protected val _currentMs = MutableStateFlow(0L)
    override val currentMs: StateFlow<Long> = _currentMs.asStateFlow()

    protected val _positionReported = MutableStateFlow(false)
    override val positionReported: StateFlow<Boolean> = _positionReported.asStateFlow()

    protected val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    protected val _error = MutableStateFlow<Throwable?>(null)
    override val error: StateFlow<Throwable?> = _error.asStateFlow()

    protected val _isBuffering = MutableStateFlow(false)
    override val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _bufferPercent = MutableStateFlow(0f)
    override val bufferPercent: StateFlow<Float> = _bufferPercent.asStateFlow()

    protected val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()
    override val maxVolume: Float = 2f

    protected val _isMuted = MutableStateFlow(false)
    override val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    override val audioTracks: StateFlow<List<TrackInfo>> = _audioTracks.asStateFlow()

    private val _selectedAudioTrack = MutableStateFlow<TrackInfo?>(null)
    override val selectedAudioTrack: StateFlow<TrackInfo?> = _selectedAudioTrack.asStateFlow()

    protected val _subtitleTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    override val subtitleTracks: StateFlow<List<TrackInfo>> = _subtitleTracks.asStateFlow()

    protected val _selectedSubtitleTrack = MutableStateFlow<TrackInfo?>(null)
    override val selectedSubtitleTrack: StateFlow<TrackInfo?> = _selectedSubtitleTrack.asStateFlow()

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    override val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    private val _currentChapter = MutableStateFlow<Chapter?>(null)
    override val currentChapter: StateFlow<Chapter?> = _currentChapter.asStateFlow()

    protected val _playbackSpeed = MutableStateFlow(1f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _aspectRatio = MutableStateFlow(AspectRatio.FIT)
    override val aspectRatio: StateFlow<AspectRatio> = _aspectRatio.asStateFlow()

    protected val _videoSize = MutableStateFlow(0 to 0)
    override val videoSize: StateFlow<Pair<Int, Int>> = _videoSize.asStateFlow()

    private val _pictureCrop = MutableStateFlow<PictureCrop?>(null)
    internal val pictureCrop: StateFlow<PictureCrop?> = _pictureCrop.asStateFlow()

    /** True while the caller has asked for a silent preload: opened like any title, held as soon as it can be. */
    internal val isPreloading = MutableStateFlow(false)

    @Volatile protected var loadedSource: MediaSource.Url? = null
        private set
    @Volatile protected var sourceLive = false
        private set
    @Volatile private var hasStartedPlaying = false
    @Volatile private var spuDisabled = false
    @Volatile protected var released = false

    // libVLC 4 stops an input asked to pause before it can, and every stream is unpausable
    // while it opens: a pause there threw the open away, resume seek and all. One asked for
    // early is held until the input can take it.
    @Volatile private var pausable = false
    @Volatile private var pauseWhenPausable = false

    // Events from an input we stopped or replaced trail in after it; until the next one
    // opens they describe nothing on screen.
    @Volatile private var inputRetired = false

    protected var normalization = 1f

    protected fun userVolume() = vlcVolume(_volume.value, normalization, maxVolume)

    // libVLC is driven from one thread per player, so a call that never returns (a stop
    // waiting on an input whose decoder deadlocked) would hold every command behind it.
    // Not [scope]: release() cancels that, and the lane still has the release itself to run.
    // Lazy, as the engine is built from the platform's own fields.
    protected val lane: CommandLane<B> by lazy {
        CommandLane(
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            wedgedAfterMs = WedgedVlcCallMs,
            newEngine = ::newEngine,
            onReplaced = { stuck, _ ->
                adoptFreshPlayer(stuck)
                val speed = _playbackSpeed.value
                vlc { setRate(speed) }
            },
            resumeTitle = { resumeTitle() },
        )
    }

    protected val binding: B get() = lane.current

    override val stalls: Flow<PlayerStall> get() = lane.stalls

    protected abstract fun newEngine(): CommandLane.Engine<B>

    /** Hands [source] to the player and starts it, on the lane. */
    protected abstract fun B.startTitle(source: MediaSource.Url)

    /** A fresh player has taken over from [stuck], ahead of the command that was waiting on it. */
    protected open suspend fun adoptFreshPlayer(stuck: B) {}

    protected abstract fun updateVideoSize()

    protected open fun beforeOpen(source: MediaSource.Url) {}
    protected open fun onSeek(ms: Long) {}
    protected open fun onStopped() {}
    protected open fun onAudible() {}
    protected open fun fadeOutThen(pause: () -> Unit) = pause()
    protected open fun onAudioTrackSelected() {}
    protected open fun publishPositionEvents(): Boolean = true

    internal fun vlc(block: B.() -> Unit) {
        lane.post(block)
    }

    protected fun post(task: () -> Unit): Boolean = lane.post { task() }

    protected suspend fun <T> onVlc(block: B.() -> T): T? = lane.call(block)

    private fun postSettled(task: () -> Unit): Boolean {
        if (released) return false
        scope.launch {
            delay(TrackRefreshSettleMs)
            post(task)
        }
        return true
    }

    // A seek queued behind a stop that is waiting on a stalled read is stale by the time it runs.
    private val seeks = LatestWins<Long>(::post) { binding.setTime(it * 1000) }

    protected val trackRefresh = LatestWins<Unit>(::postSettled) {
        reloadTracks()
        updateVideoSize()
    }

    init {
        scope.launch { watchFrozenClock(_state, _currentMs, _isBuffering, _error, held = { isPreloading.value }) }
    }

    // --- Events, from the platform's listener for the current player ---

    protected fun onOpening() {
        inputRetired = false
        hasStartedPlaying = false
        _positionReported.value = false
        _state.value = PlayerState.LOADING
        _isBuffering.value = true
    }

    protected fun onPlaying() {
        if (inputRetired) return
        _isBuffering.value = false
        _bufferPercent.value = 1f
        hasStartedPlaying = true
        if (isPreloading.value || pauseWhenPausable) {
            // Held as soon as it can be, so position can't advance silently and audio can't
            // reach the speaker before unmute.
            holdIfAsked()
            _state.value = PlayerState.PAUSED
        } else {
            _state.value = PlayerState.PLAYING
            onAudible()
        }
        trackRefresh.offer(Unit)
    }

    protected fun onPaused() {
        if (!inputRetired) _state.value = PlayerState.PAUSED
    }

    /** libVLC 4's stop: the same event ends a title, follows our own stop and a stream that dropped out. */
    protected fun onStopping() {
        if (inputRetired) return
        val next = stateOnStopping(_state.value, requested = false, _currentMs.value, _durationMs.value, sourceLive)
        if (next == PlayerState.ERROR) _error.value = StreamStoppedShort(_currentMs.value, _durationMs.value, sourceLive)
        _state.value = next
    }

    protected fun onError() {
        if (inputRetired) return
        _error.value = RuntimeException("VLC playback error")
        _state.value = PlayerState.ERROR
    }

    /** [fraction] of the cache filled. */
    protected fun onBuffering(fraction: Float) {
        if (inputRetired) return
        val filled = fraction.coerceIn(0f, 1f)
        _bufferPercent.value = filled
        if (!hasStartedPlaying) return
        _isBuffering.value = filled < 1f
        _state.value = stateOnBuffering(_state.value, full = filled >= 1f)
    }

    protected fun onPausable(canPause: Boolean) {
        if (inputRetired) return
        pausable = canPause
        if (canPause && hasStartedPlaying) holdIfAsked()
    }

    protected open fun onPosition(timeUs: Long) {
        if (inputRetired) return
        val timeMs = timeUs / 1000
        if (timeMs != _currentMs.value) {
            val resolved = stateAfterClockAdvance(_state.value, isPreloading.value)
            if (resolved != _state.value) {
                _state.value = resolved
                _isBuffering.value = false
            }
        }
        if (publishPositionEvents()) _currentMs.value = publishedPosition(_currentMs.value, timeMs)
        _positionReported.value = true
    }

    protected open fun onLength(lengthUs: Long) {
        if (!inputRetired) _durationMs.value = lengthUs / 1000
    }

    protected fun onTracksChanged() {
        if (!inputRetired) trackRefresh.offer(Unit)
    }

    // --- Commands ---

    override fun load(source: MediaSource) {
        when (source) {
            is MediaSource.Url -> open(source)
        }
    }

    protected fun open(source: MediaSource.Url) {
        inputRetired = true
        sourceLive = source.live
        loadedSource = source
        _state.value = PlayerState.LOADING
        _isBuffering.value = true
        _bufferPercent.value = 0f
        _currentMs.value = source.startTimeSec?.let { it * 1000L } ?: 0L
        _positionReported.value = false
        _durationMs.value = 0L
        _error.value = null
        hasStartedPlaying = false
        seeks.clear()
        beforeOpen(source)
        lane.replaceTitle(
            stop = {
                pausable = false
                pauseWhenPausable = false
                stopAndWait()
            },
        ) {
            startAt(source, source.startTimeSec?.let { it * 1000L })
        }
    }

    // Not :start-time: libVLC 4 makes that the title's start, and reports times and length
    // from it. Before play() there is no input to take the seek; straight after, it is the
    // first thing the input does once the demuxer is open. Fast, so a resume lands on the
    // keyframe before the mark instead of decoding up to it.
    private fun B.startAt(source: MediaSource.Url, atMs: Long?) {
        startTitle(source)
        atMs?.let { setTime(it * 1000, fast = true) }
    }

    // On a fresh player that took over from one stuck mid-title, ahead of the command that
    // was waiting on it. The stuck one is left alone: any call into it could hang as well.
    private fun B.resumeTitle() {
        val source = loadedSource ?: return
        val atMs = _currentMs.value.takeUnless { source.live }
        inputRetired = true
        pausable = false
        pauseWhenPausable = _state.value == PlayerState.PAUSED
        atMs?.let(::onSeek)
        startAt(source, atMs)
    }

    override fun play() {
        vlc {
            pauseWhenPausable = false
            // A resume queues behind a pause still on its way; play() starts a title that
            // stopped or ended.
            setPause(false)
            play()
            // A title read as held before its hold landed never paused, so it reports no
            // playing to resume with.
            if (_state.value == PlayerState.PAUSED && inputState == VlcInputState.Playing && !isPreloading.value) {
                _state.value = PlayerState.PLAYING
                onAudible()
            }
        }
    }

    override fun pause() {
        fadeOutThen { vlc { if (pausable) setPause(true) else pauseWhenPausable = true } }
    }

    // On the lane, so a play given while the hold is on its way cancels it rather than racing it.
    private fun holdIfAsked() = vlc {
        if (pausable && (isPreloading.value || pauseWhenPausable)) setPause(true)
    }

    override fun togglePlayPause() {
        when (playPauseIntent(_state.value)) {
            PlayPauseIntent.Play -> play()
            PlayPauseIntent.Pause -> pause()
            PlayPauseIntent.None -> {}
        }
    }

    override fun stop() {
        inputRetired = true
        loadedSource = null
        seeks.clear()
        lane.supersede {
            pauseWhenPausable = false
            stopAndWait()
        }
        _state.value = PlayerState.IDLE
        _currentMs.value = 0L
        _positionReported.value = false
        _durationMs.value = 0L
        _isBuffering.value = false
        _bufferPercent.value = 0f
        hasStartedPlaying = false
        pausable = false
        onStopped()
    }

    override fun seekTo(ms: Long) {
        val clamped = clampSeekTarget(ms, _durationMs.value)
        if (clamped == _currentMs.value && _positionReported.value) return
        _currentMs.value = clamped
        onSeek(clamped)
        seeks.offer(clamped)
    }

    override fun skip(deltaMs: Long) = seekTo(clampSeekTarget(_currentMs.value + deltaMs, _durationMs.value))

    override fun volumeUp(step: Float) = setVolume(_volume.value + step)
    override fun volumeDown(step: Float) = setVolume(_volume.value - step)

    override fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        vlc { setRate(speed) }
    }

    override fun selectAudioTrack(track: TrackInfo?) {
        _selectedAudioTrack.value = track
        vlc {
            if (track == null) deselect(VlcTrackType.Audio) else selectTrack(VlcTrackType.Audio, track.id)
            _selectedAudioTrack.value = track
            onAudioTrackSelected()
        }
    }

    override fun selectSubtitleTrack(track: TrackInfo?) {
        spuDisabled = track == null
        _selectedSubtitleTrack.value = track
        vlc {
            if (track == null) deselect(VlcTrackType.Text) else selectTrack(VlcTrackType.Text, track.id)
            _selectedSubtitleTrack.value = track
        }
    }

    override fun setChapter(chapter: Chapter) {
        val index = _chapters.value.indexOf(chapter)
        if (index < 0) return
        _currentChapter.value = chapter
        vlc { setChapter(index) }
    }

    override fun setAspectRatio(ratio: AspectRatio) {
        _aspectRatio.value = ratio
    }

    override fun setPictureCrop(crop: PictureCrop?) {
        _pictureCrop.value = crop
    }

    private fun reloadTracks() {
        val player = binding
        val audio = player.tracks(VlcTrackType.Audio)
        _audioTracks.value = audio
        val currentAudio = player.selectedTrackId(VlcTrackType.Audio)
        _selectedAudioTrack.value = audio.find { it.id == currentAudio }

        val subtitles = player.tracks(VlcTrackType.Text)
        _subtitleTracks.value = subtitles
        // VLC picks a default SPU track whenever the elementary streams are parsed, which
        // lands after the app has already asked for none — leave it and its rendering
        // stacks on top of the server-VTT overlay.
        val currentSub = player.selectedTrackId(VlcTrackType.Text)
        if (spuDisabled && currentSub != null) player.deselect(VlcTrackType.Text)
        _selectedSubtitleTrack.value = if (spuDisabled) null else subtitles.find { it.id == currentSub }

        _chapters.value = player.chapters()
    }
}
