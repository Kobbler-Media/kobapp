package dev.kobbler.kobplayer

import android.app.ActivityManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

// Often enough to settle a good device quickly, rarely enough to cost nothing.
private const val FieldDecodeProbePollMs = 500L

// A paused title that cannot seek never redraws; the held frame must still go eventually.
private const val PictureReturnTimeoutMs = 3_000L

// Sought before libVLC 4 has reselected the video track for a returned surface, a paused
// input wedges and never takes the resume. The reselection is the cue; this is the fallback.
private const val PausedRedrawSettleMs = 1_000L

private const val FrameRateProbeSettleMs = 1_000L
private const val FrameRateProbeWindowMs = 4_000L

// A remux opens with an ESAdded per stream, dozens in one burst: one refresh answers them all.
private const val TrackRefreshSettleMs = 50L
private const val AudioClockPollMs = 250L

// A stop or seek answers in well under a second; one that has not after this is stuck in
// libVLC, and whatever is waiting behind it moves to a fresh player.
private const val WedgedVlcCallMs = 1_500L

// A view rebuilt on the next frame has its surface within a few; past this the stream is
// opened without one rather than held up.
private const val FreshSurfaceWaitMs = 1_000L

/**
 * Android VLC player implementation using libvlc-android.
 *
 * Uses libvlc for all codec handling — no hardware decoder dependency,
 * so EAC3, DTS, etc. just work.
 */
internal class AndroidPlayer(
    private val context: Context,
    parentContext: CoroutineContext,
    private val config: PlayerConfig,
) : Player {

    private val scope = CoroutineScope(
        parentContext + SupervisorJob(parentContext[Job])
    )

    // No --android-display-chroma: forcing one makes the display allocate packed RGB
    // for every picture, which at 1920x1088 is ~8 MB each and starves the pool the
    // decoder draws from — VLC gives up with "buffer deadlock prevented" and the
    // screen stays black while the audio plays on. SD fits and so looked fine.
    // Left to itself VLC picks a chroma the decoder can hand over directly.
    val libVLC = LibVLC(context, ArrayList<String>().apply {
        add("--network-caching=${config.networkCacheMs}")
        val lowRam = context.getSystemService(ActivityManager::class.java).isLowRamDevice
        addAll(if (lowRam) config.readAhead.forLowRam().vlcOptions else config.readAhead.vlcOptions)
        // `adb shell setprop log.tag.kobplayer VERBOSE` turns it on for any build.
        if (config.verboseLogging || android.util.Log.isLoggable("kobplayer", android.util.Log.VERBOSE)) {
            add("-vv")
        }
        if (!config.hardwareAcceleration) {
            add("--avcodec-hw=none")
        }
    })

    // Releasing a player waits for its inputs to wind down, which takes as long as a
    // stalled network read does: on the main thread, a stream that hung while opening
    // took the app down with an ANR. One serial thread keeps libVLC calls off the UI and
    // the play/pause/seek that follow a load in order behind it, and anything that finds
    // that thread stuck in a call gets a new player rather than waiting on it.
    // View attach and detach stay on main: they touch the SurfaceHolder.
    // Not [scope]: release() cancels that, and the lane still has the release itself to run.
    // Its loop ends with the queue that close() closes.
    private val lane = CommandLane(
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        wedgedAfterMs = WedgedVlcCallMs,
        newEngine = ::newEngine,
        onReplaced = { stuck, _ -> withContext(Dispatchers.Main) { adoptFreshPlayer(stuck) } },
        resumeTitle = { resumeTitle() },
    )

    val vlcPlayer: MediaPlayer get() = lane.current

    private fun newEngine(): CommandLane.Engine<MediaPlayer> {
        val thread = Executors.newSingleThreadExecutor { Thread(it, "kobplayer-vlc").apply { isDaemon = true } }
        val player = MediaPlayer(libVLC).also(::listenTo)
        return CommandLane.Engine(player, thread.asCoroutineDispatcher()) {
            it.setEventListener(null)
            it.stop()
            it.release()
            thread.shutdown()
        }
    }

    private fun post(task: () -> Unit): Boolean = lane.post { task() }

    private fun vlc(block: MediaPlayer.() -> Unit) {
        lane.post(block)
    }

    private suspend fun <T> onVlc(block: MediaPlayer.() -> T): T? = lane.call(block)

    @Volatile private var released = false

    private fun postSettled(task: () -> Unit): Boolean {
        if (released) return false
        scope.launch {
            delay(TrackRefreshSettleMs)
            post(task)
        }
        return true
    }

    // A seek queued behind a stop that is waiting on a stalled read is stale by the time it runs.
    private val seeks = LatestWins<Long>(::post) { vlcPlayer.setTime(it) }
    private val volumes = LatestWins<Int>(::post) { vlcPlayer.volume = it }
    private val trackRefresh = LatestWins<Unit>(::postSettled) {
        reloadTracks()
        updateVideoSize()
    }

    // Not settled like the refresh: a device chosen after the audio output has opened restarts it.
    private val audioRoute = LatestWins<Unit>(::post) { applyAudioOutput() }

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

    // TimeChanged rides on pictures, so with :no-video libVLC reports the clock only every
    // several seconds and an audiobook's elapsed time visibly lurched. The poll is then the
    // only clock: TimeChanged runs ahead of what is heard by the output latency, and the two
    // taking turns walked the position back and forth.
    private val audioClock = PolledClock { SystemClock.elapsedRealtime() }

    init {
        if (config.audioOnly) scope.launch {
            _state.collectLatest { state ->
                while (state == PlayerState.PLAYING) {
                    delay(AudioClockPollMs)
                    val time = onVlc { time } ?: break
                    if (time >= 0) {
                        _currentMs.value = audioClock.publish(_currentMs.value, time)
                        _positionReported.value = true
                    }
                }
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

    override val native: NativePlayer get() = NativePlayer.LibVlc(vlcPlayer, libVLC)

    // --- Audio focus ---

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var hasAudioFocus = false
    private val focusResume = FocusResume()

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (config.audioOnly) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                val change = focusChangeOf(focusChange) ?: return@setOnAudioFocusChangeListener
                if (change == FocusChange.Loss) hasAudioFocus = false
                if (change == FocusChange.Gain) hasAudioFocus = true
                // A silent preload holds no audible claim: a call ending must not start it.
                if (isPreloading.value) return@setOnAudioFocusChangeListener
                when (focusResume.onChange(change, _state.value == PlayerState.PLAYING)) {
                    FocusAction.Pause -> pauseIfPlaying()
                    FocusAction.Duck -> if (!_isMuted.value) volumes.offer(((_volume.value * 100) * 0.3f).toInt())
                    FocusAction.Resume -> {
                        restoreVolume()
                        resume()
                    }
                    FocusAction.Restore -> restoreVolume()
                    FocusAction.None -> {}
                }
            }
            .build()
    }

    private fun requestAudioFocus() {
        if (!hasAudioFocus) {
            val result = audioManager.requestAudioFocus(focusRequest)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun restoreVolume() {
        volumes.offer(if (_isMuted.value) 0 else (_volume.value * 100).toInt())
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            hasAudioFocus = false
        }
    }

    // --- Surface management for clean preload ---
    // The vout stays attached throughout, preload included. Detaching it to hide the
    // preload meant the elementary stream was opened with no window to hand a decoder,
    // and MediaCodec cannot be chosen without one — every playback fell through to
    // libavcodec and software-decoded H.264 for its whole duration, which on a TV box is
    // the difference between smooth and stuttering. Preload is silent, not invisible; the
    // preparing scrim is what the viewer sees over it.
    private val surfaces = SurfaceSlot<android.view.SurfaceHolder>()
    private var voutAttached = false

    // VLC's Android display blends subpictures only onto a window of their own; without
    // one it opens "without subtitles support" and every embedded track draws nothing.
    internal var subtitlesView: android.view.SurfaceView? = null

    override val videoOutputReady: StateFlow<Boolean> = surfaces.ready
    internal val surfaceEpoch: StateFlow<Int> = surfaces.epoch

    internal fun onSurfaceCreated(holder: android.view.SurfaceHolder) {
        // Attached immediately, preload included: a decoder is chosen when the stream
        // opens and cannot be handed a window that turns up afterwards.
        surfaces.created(holder)
        attachVoutIfReady()
        pictureTimeout?.cancel()
        when (surfaceReturnRedraw(_state.value)) {
            SurfaceReturnRedraw.Now -> redrawPicture()
            SurfaceReturnRedraw.Deferred -> {
                awaitingPicture = true
                deferredRedraw?.cancel()
                deferredRedraw = scope.launch {
                    delay(PausedRedrawSettleMs)
                    deferredRedraw = null
                    redrawPicture()
                }
            }
            SurfaceReturnRedraw.None -> markPictureReturned()
        }
    }

    private var deferredRedraw: Job? = null

    private fun redrawPicture() {
        awaitingPicture = true
        vlc { if (isSeekable) setTime(time) }
        pictureTimeout?.cancel()
        pictureTimeout = scope.launch {
            delay(PictureReturnTimeoutMs)
            markPictureReturned()
        }
    }

    private fun resume() {
        vlc {
            pauseWhenPausable = false
            play()
        }
        deferredRedraw?.let {
            it.cancel()
            deferredRedraw = null
            redrawPicture()
        }
    }

    // Bumped once the surface that came back has been drawn into again (or given up on).
    private val _pictureReturns = MutableStateFlow(0)
    internal val pictureReturns: StateFlow<Int> = _pictureReturns.asStateFlow()
    @Volatile private var awaitingPicture = false
    private var pictureTimeout: Job? = null

    private fun markPictureReturned() {
        awaitingPicture = false
        pictureTimeout?.cancel()
        _pictureReturns.value++
    }

    internal fun onSurfaceDestroyed(holder: android.view.SurfaceHolder) {
        if (!surfaces.destroyed(holder)) return
        deferredRedraw?.cancel()
        deferredRedraw = null
        detachVout()
    }

    // surfaceChanged can land before the vout is attached, and the only callback carrying
    // the real dimensions is then already gone. Bank them so the attach can apply them;
    // VLC sizes subtitles to the window it was last told about.
    private var pendingSurfaceWidth = 0
    private var pendingSurfaceHeight = 0

    /** True when the player is silently preloading (decode without render). */
    internal val isPreloading = MutableStateFlow(false)

    init {
        scope.launch { watchFrozenClock(_state, _currentMs, _isBuffering, _error, held = { isPreloading.value }) }
    }

    private val _contentFrameRate = MutableStateFlow(0f)
    internal val contentFrameRate: StateFlow<Float> = _contentFrameRate.asStateFlow()

    private var frameRateProbe: Job? = null

    private val _modeSwitchAllowed = MutableStateFlow(false)
    internal val modeSwitchAllowed: StateFlow<Boolean> = _modeSwitchAllowed.asStateFlow()

    private val _matchDisplayRefresh = MutableStateFlow(false)
    internal val matchDisplayRefresh: StateFlow<Boolean> = _matchDisplayRefresh.asStateFlow()

    override fun setMatchDisplayRefresh(enabled: Boolean) {
        _matchDisplayRefresh.value = enabled
    }

    private val _pictureCrop = MutableStateFlow<PictureCrop?>(null)
    internal val pictureCrop: StateFlow<PictureCrop?> = _pictureCrop.asStateFlow()

    override fun setPictureCrop(crop: PictureCrop?) {
        _pictureCrop.value = crop
    }

    private fun updateModeSwitchAllowed() {
        _modeSwitchAllowed.value = worthAModeSwitch(_durationMs.value, sourceLive)
    }

    // Matroska without a default duration declares no rate, so it is counted instead: the
    // pictures shown over a stretch of uninterrupted 1x playback.
    private fun armFrameRateProbe() {
        frameRateProbe?.cancel()
        if (config.audioOnly) return
        frameRateProbe = scope.launch {
            val rate = probeFrameRate(_state, FrameRateProbeSettleMs, FrameRateProbeWindowMs, ::cleanPlaybackSample)
            if (_contentFrameRate.value == 0f) _contentFrameRate.value = rate
        }
    }

    private suspend fun cleanPlaybackSample(): Pair<Long, Long>? {
        if (_state.value != PlayerState.PLAYING || _isBuffering.value || _playbackSpeed.value != 1f) return null
        return onVlc { currentMedia?.stats?.displayedPictures?.let { it to SystemClock.elapsedRealtime() } }
    }

    private val _decodedPicture = MutableStateFlow<DecodedPicture?>(null)
    internal val decodedPicture: StateFlow<DecodedPicture?> = _decodedPicture.asStateFlow()

    // With a layout listener VLC hands the whole decoded buffer to the surface and leaves
    // placement to us. Left to place the picture itself it fit it to whatever window size
    // reached its display last — a stale one during a rotation left the picture small
    // and boxed on all sides — and it trusted decoder geometry some chipsets pad.
    private val videoLayoutListener =
        IVLCVout.OnNewVideoLayoutListener { _, width, height, visibleWidth, visibleHeight, _, _ ->
            _decodedPicture.value = DecodedPicture(width, height, visibleWidth, visibleHeight)
        }

    internal fun onSurfaceSize(width: Int, height: Int) {
        pendingSurfaceWidth = width
        pendingSurfaceHeight = height
        if (voutAttached) vlcPlayer.vlcVout.setWindowSize(width, height)
    }

    private fun attachVoutIfReady() {
        val holder = surfaces.current ?: return
        if (voutAttached) return
        val vout = vlcPlayer.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
        vout.setVideoSurface(holder.surface, holder)
        subtitlesView?.let(vout::setSubtitlesView)
        vout.attachViews(videoLayoutListener)
        voutAttached = true
        if (pendingSurfaceWidth > 0 && pendingSurfaceHeight > 0) {
            vout.setWindowSize(pendingSurfaceWidth, pendingSurfaceHeight)
        }
    }

    private fun detachVout() {
        val vout = vlcPlayer.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
        voutAttached = false
    }

    // --- Internal ---

    @Volatile private var currentMedia: Media? = null
    @Volatile private var openedSource: MediaSource.Url? = null
    @Volatile private var stopRequested = false
    @Volatile private var sourceLive = false

    private var fieldDecodeProbe: Job? = null
    @Volatile private var hasVideoTrack = false
    private var forceSoftwareDecode = false
    private val fieldDecodeVerdict: FieldDecodeVerdict get() = DeviceFieldDecode.verdict(context)

    init {
        if (!config.audioOnly) DeviceFieldDecode.selfTestIfUnknown(context)
    }

    private fun listenTo(player: MediaPlayer) {
        player.setEventListener { event ->
            // An abandoned player's events are about a title nobody is watching any more.
            if (player !== vlcPlayer) return@setEventListener
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    stopRequested = false
                    _positionReported.value = false
                    _state.value = PlayerState.LOADING
                    _isBuffering.value = true
                }
                MediaPlayer.Event.Buffering -> {
                    val pct = event.buffering
                    _bufferPercent.value = (pct / 100f).coerceIn(0f, 1f)
                    _isBuffering.value = pct < 100f
                    _state.value = stateOnBuffering(_state.value, full = pct >= 100f)
                }
                MediaPlayer.Event.Playing -> {
                    // A preload is opened like any other and held as soon as it can be.
                    if (isPreloading.value || pauseWhenPausable) {
                        pauseIfPlaying()
                        _state.value = PlayerState.PAUSED
                    } else {
                        _state.value = PlayerState.PLAYING
                    }
                    _isBuffering.value = false
                    trackRefresh.offer(Unit)
                }
                MediaPlayer.Event.PausableChanged -> {
                    pausable = event.pausable
                    if (pausable && (isPreloading.value || pauseWhenPausable)) pauseIfPlaying()
                }
                MediaPlayer.Event.Paused -> {
                    _state.value = PlayerState.PAUSED
                }
                MediaPlayer.Event.EndReached -> {
                    val next = stateOnStopping(_state.value, stopRequested, _currentMs.value, _durationMs.value, sourceLive)
                    if (next == PlayerState.ERROR) _error.value = StreamStoppedShort(_currentMs.value, _durationMs.value, sourceLive)
                    _state.value = next
                }
                MediaPlayer.Event.EncounteredError -> {
                    _error.value = RuntimeException("VLC playback error")
                    _state.value = PlayerState.ERROR
                }
                MediaPlayer.Event.TimeChanged -> {
                    val time = event.timeChanged
                    if (awaitingPicture) markPictureReturned()
                    if (time != _currentMs.value) {
                        val resolved = stateAfterClockAdvance(_state.value, isPreloading.value)
                        if (resolved != _state.value) {
                            _state.value = resolved
                            _isBuffering.value = false
                        }
                    }
                    if (!config.audioOnly) _currentMs.value = publishedPosition(_currentMs.value, time)
                    _positionReported.value = true
                }
                MediaPlayer.Event.LengthChanged -> {
                    _durationMs.value = event.lengthChanged
                    updateModeSwitchAllowed()
                }
                MediaPlayer.Event.ESAdded -> {
                    if (event.esChangedType == IMedia.Track.Type.Audio) audioRoute.offer(Unit)
                    if (event.esChangedType == IMedia.Track.Type.Video) hasVideoTrack = true
                    trackRefresh.offer(Unit)
                }
                MediaPlayer.Event.ESSelected -> {
                    if (event.esChangedType == IMedia.Track.Type.Audio) audioRoute.offer(Unit)
                    trackRefresh.offer(Unit)
                    if (event.esChangedType == IMedia.Track.Type.Video) deferredRedraw?.let {
                        it.cancel()
                        deferredRedraw = null
                        redrawPicture()
                    }
                }
                MediaPlayer.Event.Vout -> trackRefresh.offer(Unit)
            }
        }
    }

    // --- Commands ---

    // Localhost auth proxy for VLC (libvlc 3.x can't send custom HTTP headers)

    @Volatile private var spuDisabled = false

    override fun load(source: MediaSource) {
        when (source) {
            is MediaSource.Url -> {
                sourceLive = source.live
                forceSoftwareDecode = false
                open(source)
                armFieldDecodeProbe(source)
            }
        }
    }

    private fun open(source: MediaSource.Url) {
        deferredRedraw?.cancel()
        deferredRedraw = null
        hasVideoTrack = false
        // The last title's geometry would size this one's frame until its own tracks report.
        _videoSize.value = 0 to 0
        _decodedPicture.value = null
        _contentFrameRate.value = 0f
        armFrameRateProbe()
        _state.value = PlayerState.LOADING
        _isBuffering.value = true
        _bufferPercent.value = 0f
        audioClock.reset()
        _currentMs.value = source.startTimeSec?.let { it * 1000L } ?: 0L
        _positionReported.value = false
        _durationMs.value = 0L
        updateModeSwitchAllowed()
        _error.value = null

        val previous = currentMedia
        val media = media(source)
        openedSource = source
        currentMedia = media
        // Before play(), never after. play() opens the elementary stream, and that
        // is the one moment a decoder is chosen; a window that arrives later is a
        // window MediaCodec was never offered.
        attachVoutIfReady()
        // Only grab audio focus if not preloading (preload should not
        // steal focus from other apps like music players).
        if (!isPreloading.value) requestAudioFocus()
        seeks.clear()
        lane.replaceTitle(
            stop = {
                stopRequested = true
                pausable = false
                pauseWhenPausable = false
                // Media handed to a libVLC 4 player that is still playing is swapped in gaplessly:
                // the player never leaves its playing state, so the new title reports no opening
                // and its end reads as the stop asked for here. stop() returns once it has stopped
                // — or never, for a live stream the server ended in the same moment.
                stop()
            },
        ) {
            this.media = media
            previous?.release()
            play()
            // Not :start-time: libVLC 4 clips the title there, reporting time and length from
            // the start point. Before play() there is no input to take the seek; straight
            // after, it is the first thing the input does once the demuxer is open. Fast, so
            // a resume lands on the keyframe before the mark instead of decoding up to it.
            source.startTimeSec?.let { setTime(it * 1000L, true) }
        }
    }

    private fun media(source: MediaSource.Url): Media {
        val headers = source.headers.mapKeys { it.key.lowercase() }
        return Media(libVLC, Uri.parse(source.url)).apply {
            addOption(":http-user-agent=${headers["user-agent"] ?: config.httpUserAgent}")
            addOption(":network-caching=${source.networkCacheMs ?: config.networkCacheMs}")
            addOption(":${vlcDemuxOption(source)}")
            if (config.audioOnly) addOption(":no-video")
            headers["referer"]?.let { addOption(":http-referrer=$it") }
            if (!config.hardwareAcceleration) {
                addOption(":avcodec-hw=none")
            }
            // What libVLC 4's Media.setHWDecoderEnabled(false) emits. Not `:codec=all`, the
            // libVLC 3 spelling: under 4 that is just the default priority list, and the
            // decoder restart on the first format change lands on MediaCodec anyway. This
            // withholds the decoder device MediaCodec cannot open without, for the whole
            // stream — so it is set only where this device has been seen to need it.
            if (forceSoftwareDecode || softwareDecodeRequired(source.fieldCoded, fieldDecodeVerdict)) {
                addOption(":no-hw-dec")
            }
            source.audioLanguage?.let { addOption(":audio-language=$it") }
            // `none` rather than `--no-spu`: no-spu disables the whole subtitle
            // pipeline, so a viewer turning subtitles on later would be refused
            // silently. This only declines the automatic pick.
            if (source.subtitlesOff) addOption(":sub-language=none")
            else source.subtitleOrdinal?.let { addOption(":sub-track=$it") }
        }
    }

    // On a fresh player that took over from one stuck mid-title, ahead of the pause or seek
    // that was waiting on it. The stuck player keeps its media: releasing it under a call
    // still running in it is a native crash.
    private fun MediaPlayer.resumeTitle() {
        val source = openedSource ?: return
        val atMs = _currentMs.value
        stopRequested = false
        pausable = false
        pauseWhenPausable = _state.value == PlayerState.PAUSED
        val media = media(source)
        currentMedia = media
        this.media = media
        play()
        if (!source.live) {
            audioClock.seeked(atMs)
            setTime(atMs, true)
        }
    }

    /**
     * Reopen a tune in software once its decoder has run long enough without a new picture.
     *
     * VLC raises no error for a decoder that accepts field pictures and emits none, so the
     * only evidence is the media clock running against pictures that stopped coming.
     */
    private fun armFieldDecodeProbe(source: MediaSource.Url) {
        fieldDecodeProbe?.cancel()
        if (config.audioOnly || !watchesForSilentDecoder(source.fieldCoded, fieldDecodeVerdict)) return
        fieldDecodeProbe = scope.launch {
            val watch = SilentDecoderWatch()
            while (!watch.silent) sampleDecoder(watch)
            forceSoftwareDecode = true
            open(source.copy(startTimeSec = if (source.live) source.startTimeSec else (watch.lastClockMs / 1000).toInt()))
        }
    }

    // VLC's own clock: the published position rides on pictures, and here there are none.
    private suspend fun sampleDecoder(watch: SilentDecoderWatch) {
        delay(FieldDecodeProbePollMs)
        val (clockMs, pictures) = onVlc { time to (currentMedia?.stats?.displayedPictures ?: 0L) } ?: return
        watch.sample(clockMs, pictures, hasVideoTrack)
    }

    override fun play() {
        focusResume.forget()
        requestAudioFocus()
        resume()
    }

    override fun pause() {
        focusResume.forget()
        vlc {
            if (pausable) pauseIfPlaying() else pauseWhenPausable = true
        }
    }

    // libVLC 4 stops an input it cannot pause yet, and every stream is unpausable while
    // it opens: a pause there threw the open away, and the play after it opened it again.
    // One asked for early is held until the input can take it.
    @Volatile private var pausable = false
    @Volatile private var pauseWhenPausable = false

    private fun pauseIfPlaying() {
        vlc { if (isPlaying && pausable) pause() }
    }

    override fun stop() {
        openedSource = null
        deferredRedraw?.cancel()
        deferredRedraw = null
        fieldDecodeProbe?.cancel()
        frameRateProbe?.cancel()
        seeks.clear()
        lane.supersede {
            stopRequested = true
            pauseWhenPausable = false
            stop()
        }
        _state.value = PlayerState.IDLE
        _currentMs.value = 0L
        _positionReported.value = false
        _durationMs.value = 0L
        _isBuffering.value = false
        _bufferPercent.value = 0f
        focusResume.forget()
        abandonAudioFocus()
    }

    override fun togglePlayPause() {
        focusResume.forget()
        when (playPauseIntent(_state.value)) {
            PlayPauseIntent.Play -> resume()
            // Not vlcPlayer.pause(): libvlc's is a toggle, so it restarts a stream that
            // is not actually running. pause() carries that guard.
            PlayPauseIntent.Pause -> pauseIfPlaying()
            PlayPauseIntent.None -> {}
        }
    }

    override fun seekTo(ms: Long) {
        val clamped = clampSeekTarget(ms, _durationMs.value)
        _currentMs.value = clamped
        audioClock.seeked(clamped)
        seeks.offer(clamped)
    }

    override fun skip(deltaMs: Long) {
        seekTo(clampSeekTarget(_currentMs.value + deltaMs, _durationMs.value))
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, maxVolume)
        _volume.value = clamped
        volumes.offer((clamped * 100).toInt())
    }

    override fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        // libvlc-android: set volume to 0 for mute, restore for unmute
        if (muted) {
            volumes.offer(0)
        } else {
            volumes.offer((_volume.value * 100).toInt())
            // Grab audio focus now if unmuting (skipped during muted preload)
            requestAudioFocus()
        }
    }

    override fun warmVideoOutput() {
        attachVoutIfReady()
    }

    override fun setPreloading(preloading: Boolean) {
        val wasPreloading = isPreloading.value
        isPreloading.value = preloading
        if (preloading) {
            volumes.offer(0)
        } else if (wasPreloading) {
            attachVoutIfReady()
            restoreVolume()
            if (!_isMuted.value) requestAudioFocus()
        }
    }

    override fun volumeUp(step: Float) = setVolume(_volume.value + step)
    override fun volumeDown(step: Float) = setVolume(_volume.value - step)

    override fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        vlc { rate = speed }
    }

    override fun selectAudioTrack(track: TrackInfo?) {
        _selectedAudioTrack.value = track
        vlc {
            if (track == null) unselectTrackType(IMedia.Track.Type.Audio) else selectTrack(track.id)
            _selectedAudioTrack.value = track
            applyAudioOutput()
        }
    }

    override fun setAudioOutputMode(mode: AudioOutputMode) {
        audioOutputMode = mode
        audioRoute.offer(Unit)
    }

    @Volatile private var audioOutputMode = AudioOutputMode.AUTO
    @Volatile private var appliedAudioDevice: String? = null

    // Chosen per track, because whether a stream can go out as bitstream depends on its
    // codec. Setting a device also stops libVLC's own HDMI listener, which would otherwise
    // pick multichannel PCM for any sink that reports a compressed format.
    private fun applyAudioOutput() {
        val codec = vlcPlayer.getSelectedTrack(IMedia.Track.Type.Audio)?.codec
        val device = audioOutputDevice(audioOutputMode, codec, sinkPassthroughMask())
        if (device == appliedAudioDevice) return
        appliedAudioDevice = device
        vlcPlayer.setAudioOutputDevice(device)
    }

    @Volatile private var passthroughMask: Long? = null

    private fun sinkPassthroughMask(): Long = passthroughMask ?: encodingMask(
        *audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .flatMap { it.encodings.asIterable() }
            .toIntArray()
    ).also { passthroughMask = it }

    private val sinkWatcher = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = sinksChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = sinksChanged()
    }

    private fun sinksChanged() {
        passthroughMask = null
        if (appliedAudioDevice != null) audioRoute.offer(Unit)
    }

    init {
        audioManager.registerAudioDeviceCallback(sinkWatcher, Handler(Looper.getMainLooper()))
    }

    override fun selectSubtitleTrack(track: TrackInfo?) {
        spuDisabled = track == null
        _selectedSubtitleTrack.value = track
        vlc {
            if (track == null) unselectTrackType(IMedia.Track.Type.Text) else selectTrack(track.id)
            _selectedSubtitleTrack.value = track
        }
    }

    override fun setAspectRatio(ratio: AspectRatio) {
        _aspectRatio.value = ratio
    }

    override fun setChapter(chapter: Chapter) {
        val idx = _chapters.value.indexOf(chapter)
        if (idx >= 0) {
            _currentChapter.value = chapter
            vlc { this.chapter = idx }
        }
    }

    override suspend fun takeScreenshot(path: String) {
        // libvlc-android doesn't have a direct screenshot API
        // Consumers can use the native player for platform-specific approaches
    }

    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        audioManager.unregisterAudioDeviceCallback(sinkWatcher)
        abandonAudioFocus()
        vlcPlayer.detachViews()
        val media = currentMedia
        currentMedia = null
        lane.close {
            media?.release()
            libVLC.release()
        }
    }

    // Main thread, before the load or stop that found the old player stuck runs on the new one.
    // Its display is a layer on the surface that a detach never reaches while it is stuck:
    // shared, it drew bars around the new player's picture. So the new player gets a new
    // surface, and the stuck one's goes with the view that held it.
    private suspend fun adoptFreshPlayer(stuck: MediaPlayer) {
        println("[kobplayer] libVLC player stuck in a call for ${WedgedVlcCallMs}ms; replaced")
        if (stuck.vlcVout.areViewsAttached()) stuck.vlcVout.detachViews()
        voutAttached = false
        surfaces.renew(FreshSurfaceWaitMs)
        attachVoutIfReady()
        appliedAudioDevice = null
        audioRoute.offer(Unit)
        restoreVolume()
        val speed = _playbackSpeed.value
        vlc { rate = speed }
    }

    // --- Internal ---

    private fun updateVideoSize() {
        val track = (vlcPlayer.getSelectedTrack(IMedia.Track.Type.Video)
            ?: vlcPlayer.getTracks(IMedia.Track.Type.Video)?.firstOrNull()) as? IMedia.VideoTrack ?: return
        if (track.width > 0 && track.height > 0) {
            _videoSize.value = displaySize(track.width, track.height, track.sarNum, track.sarDen)
        }
        val declared = contentFrameRate(track.frameRateNum, track.frameRateDen)
        if (declared > 0f) {
            frameRateProbe?.cancel()
            _contentFrameRate.value = declared
        }
    }

    private fun reloadTracks() {
        _audioTracks.value = trackInfos(IMedia.Track.Type.Audio)
        val currentAudio = vlcPlayer.getSelectedTrack(IMedia.Track.Type.Audio)?.id
        _selectedAudioTrack.value = _audioTracks.value.find { it.id == currentAudio }

        _subtitleTracks.value = trackInfos(IMedia.Track.Type.Text)

        // VLC picks a default SPU track whenever the elementary streams are parsed, which
        // lands after the app has already asked for none — leave it and its rendering
        // stacks on top of the server-VTT overlay.
        val currentSub = vlcPlayer.getSelectedTrack(IMedia.Track.Type.Text)?.id
        if (spuDisabled && currentSub != null) vlcPlayer.unselectTrackType(IMedia.Track.Type.Text)
        _selectedSubtitleTrack.value =
            if (spuDisabled) null else _subtitleTracks.value.find { it.id == currentSub }
    }

    private fun trackInfos(type: Int): List<TrackInfo> =
        vlcPlayer.getTracks(type).orEmpty().mapIndexed { index, track ->
            TrackInfo(track.id, track.name ?: "Track ${index + 1}")
        }
}
