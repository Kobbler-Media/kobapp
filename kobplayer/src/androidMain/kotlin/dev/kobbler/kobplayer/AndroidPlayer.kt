package dev.kobbler.kobplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

private const val FieldDecodePrefs = "kobplayer.decoder"
private const val FieldDecodeKey = "field_picture_verdict"

// Often enough to settle a good device quickly, rarely enough to cost nothing.
private const val FieldDecodeProbePollMs = 500L

// A paused title that cannot seek never redraws; the held frame must still go eventually.
private const val PictureReturnTimeoutMs = 3_000L

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
        // `adb shell setprop log.tag.kobplayer VERBOSE` turns it on for any build.
        if (config.verboseLogging || android.util.Log.isLoggable("kobplayer", android.util.Log.VERBOSE)) {
            add("-vv")
        }
        if (!config.hardwareAcceleration) {
            add("--avcodec-hw=none")
        }
    })

    val vlcPlayer = MediaPlayer(libVLC)

    // Releasing a player waits for its inputs to wind down, which takes as long as a
    // stalled network read does: on the main thread, a stream that hung while opening
    // took the app down with an ANR. One serial thread keeps libVLC calls off the UI and
    // the play/pause/seek that follow a load in order behind it. View attach and detach
    // stay on main: they touch the SurfaceHolder.
    @Volatile private var released = false

    private val vlcThread = Executors.newSingleThreadExecutor { Thread(it, "kobplayer-vlc") }

    private fun vlc(block: MediaPlayer.() -> Unit) {
        if (released) return
        vlcThread.execute { vlcPlayer.block() }
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
                    FocusAction.Duck -> if (!_isMuted.value) vlcPlayer.volume = ((_volume.value * 100) * 0.3f).toInt()
                    FocusAction.Resume -> {
                        restoreVolume()
                        vlc { play() }
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
        vlcPlayer.volume = if (_isMuted.value) 0 else (_volume.value * 100).toInt()
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
    private var pendingSurface: android.view.SurfaceHolder? = null
    private var voutAttached = false

    private val _videoOutputReady = MutableStateFlow(false)
    override val videoOutputReady: StateFlow<Boolean> = _videoOutputReady.asStateFlow()

    internal fun onSurfaceCreated(holder: android.view.SurfaceHolder) {
        // Attached immediately, preload included: a decoder is chosen when the stream
        // opens and cannot be handed a window that turns up afterwards.
        pendingSurface = holder
        attachVoutIfReady()
        _videoOutputReady.value = true
        pictureTimeout?.cancel()
        if (redrawsOnSurfaceReturn(_state.value)) {
            awaitingPicture = true
            vlc { if (isSeekable) setTime(time) }
            pictureTimeout = scope.launch {
                delay(PictureReturnTimeoutMs)
                markPictureReturned()
            }
        } else {
            markPictureReturned()
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

    internal fun onSurfaceDestroyed() {
        _videoOutputReady.value = false
        pendingSurface = null
        detachVout()
    }

    // surfaceChanged can land before the vout is attached, and the only callback carrying
    // the real dimensions is then already gone. Bank them so the attach can apply them;
    // VLC sizes subtitles to the window it was last told about.
    private var pendingSurfaceWidth = 0
    private var pendingSurfaceHeight = 0

    /** True when the player is silently preloading (decode without render). */
    internal val isPreloading = MutableStateFlow(false)

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
        val holder = pendingSurface ?: return
        if (voutAttached) return
        val vout = vlcPlayer.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
        vout.setVideoSurface(holder.surface, holder)
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

    private var currentMedia: Media? = null
    @Volatile private var stopRequested = false

    private val presentedVideo = MutableStateFlow(false)
    private var fieldDecodeProbe: Job? = null
    private var forceSoftwareDecode = false
    private var fieldDecodeVerdict: FieldDecodeVerdict = storedFieldDecodeVerdict()

    init {
        vlcPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    stopRequested = false
                    _state.value = PlayerState.LOADING
                    _isBuffering.value = true
                }
                MediaPlayer.Event.Buffering -> {
                    val pct = event.buffering
                    _bufferPercent.value = (pct / 100f).coerceIn(0f, 1f)
                    if (pct < 100f) {
                        _isBuffering.value = true
                        if (_state.value == PlayerState.PLAYING) {
                            _state.value = PlayerState.BUFFERING
                        }
                    } else {
                        _isBuffering.value = false
                        if (_state.value == PlayerState.BUFFERING) {
                            _state.value = PlayerState.PLAYING
                        }
                    }
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
                    reloadTracks()
                    updateVideoSize()
                }
                MediaPlayer.Event.PausableChanged -> {
                    pausable = event.pausable
                    if (pausable && (isPreloading.value || pauseWhenPausable)) pauseIfPlaying()
                }
                MediaPlayer.Event.Paused -> {
                    _state.value = PlayerState.PAUSED
                }
                MediaPlayer.Event.EndReached -> {
                    _state.value = stateOnStopping(_state.value, stopRequested)
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
                    _currentMs.value = time
                }
                MediaPlayer.Event.LengthChanged -> {
                    _durationMs.value = event.lengthChanged
                }
                MediaPlayer.Event.ESAdded -> {
                    reloadTracks()
                    updateVideoSize()
                    applyAudioOutput()
                }
                MediaPlayer.Event.ESSelected -> applyAudioOutput()
                MediaPlayer.Event.Vout -> {
                    // The only evidence that the decoder emitted anything. A video track
                    // exists in the elementary stream either way, so its dimensions prove
                    // nothing; a vout does not exist until there is a picture to put in it.
                    if (event.voutCount > 0) presentedVideo.value = true
                    updateVideoSize()
                }
            }
        }
    }

    // --- Commands ---

    // Localhost auth proxy for VLC (libvlc 3.x can't send custom HTTP headers)

    private var spuDisabled = false

    override fun load(source: MediaSource) {
        when (source) {
            is MediaSource.Url -> {
                forceSoftwareDecode = false
                open(source)
                armFieldDecodeProbe(source)
            }
        }
    }

    private fun open(source: MediaSource.Url) {
        presentedVideo.value = false
        _state.value = PlayerState.LOADING
        _isBuffering.value = true
        _bufferPercent.value = 0f
        _currentMs.value = source.startTimeSec?.let { it * 1000L } ?: 0L
        _durationMs.value = 0L
        _error.value = null

        val headers = source.headers.mapKeys { it.key.lowercase() }

        val previous = currentMedia
        val uri = Uri.parse(source.url)
        val media = Media(libVLC, uri).apply {
            addOption(":http-user-agent=${headers["user-agent"] ?: config.httpUserAgent}")
            addOption(":network-caching=${source.networkCacheMs ?: config.networkCacheMs}")
            // libVLC 4 seeks a Matroska stream only to cue points it has verified, and it
            // verifies none over a network: every seek decoded the title from its first frame.
            addOption(":demux=mkv_trusted,any")
            headers["referer"]?.let { addOption(":http-referrer=$it") }
            if (!config.hardwareAcceleration) {
                addOption(":avcodec-hw=none")
            }
            // `:codec=all` is what Media.setHWDecoderEnabled(false) emits: it
            // does not deprioritise MediaCodec, it takes it away. So it is set
            // only where this device has been seen to need it.
            if (forceSoftwareDecode || softwareDecodeRequired(source.fieldCoded, fieldDecodeVerdict)) {
                addOption(":codec=all")
            }
            source.audioLanguage?.let { addOption(":audio-language=$it") }
            // `none` rather than `--no-spu`: no-spu disables the whole subtitle
            // pipeline, so a viewer turning subtitles on later would be refused
            // silently. This only declines the automatic pick.
            if (source.subtitlesOff) addOption(":sub-language=none")
            else source.subtitleOrdinal?.let { addOption(":sub-track=$it") }
        }
        currentMedia = media
        // Before play(), never after. play() opens the elementary stream, and that
        // is the one moment a decoder is chosen; a window that arrives later is a
        // window MediaCodec was never offered.
        attachVoutIfReady()
        // Only grab audio focus if not preloading (preload should not
        // steal focus from other apps like music players).
        if (!isPreloading.value) requestAudioFocus()
        vlc {
            stopRequested = true
            pausable = false
            pauseWhenPausable = false
            // Media handed to a libVLC 4 player that is still playing is swapped in gaplessly:
            // the player never leaves its playing state, so the new title reports no opening
            // and its end reads as the stop asked for here. stop() returns once it has stopped.
            stop()
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

    /**
     * Watch a field-coded tune long enough to tell a slow channel from a silent decoder.
     *
     * Nothing else can tell them apart. VLC raises no error for a decoder that accepts
     * field pictures and emits none, and sound ahead of picture is ordinary on DVB-T, so
     * the only evidence is the media clock running against a vout that never appears.
     * Judged on the clock rather than on wall time: a tune still buffering has not been
     * given its chance yet.
     */
    private fun armFieldDecodeProbe(source: MediaSource.Url) {
        fieldDecodeProbe?.cancel()
        if (!source.fieldCoded || fieldDecodeVerdict != FieldDecodeVerdict.Unknown) return
        fieldDecodeProbe = scope.launch {
            val openedAt = _currentMs.value
            while (isActive) {
                delay(FieldDecodeProbePollMs)
                if (presentedVideo.value) {
                    // Settled, and settled for good: this device is never probed again.
                    rememberFieldDecodeVerdict(FieldDecodeVerdict.HardwareDecodes)
                    return@launch
                }
                if (fieldDecodeProbeFailed(true, fieldDecodeVerdict, false, _currentMs.value - openedAt)) break
            }
            if (!isActive) return@launch
            forceSoftwareDecode = true
            open(source)
            delay(FieldDecodeProbeMs)
            rememberFieldDecodeVerdict(verdictAfterFallback(presentedVideo.value))
        }
    }

    private fun rememberFieldDecodeVerdict(verdict: FieldDecodeVerdict) {
        if (verdict == FieldDecodeVerdict.Unknown || verdict == fieldDecodeVerdict) return
        fieldDecodeVerdict = verdict
        // The one line that says why a device is on the slow path, or why it stopped being.
        println("[FieldDecode] this device: $verdict")
        context.getSharedPreferences(FieldDecodePrefs, Context.MODE_PRIVATE)
            .edit()
            .putString(FieldDecodeKey, verdict.name)
            .apply()
    }

    /**
     * The stored verdict, or what is known about the silicon before anything has been tried.
     *
     * Seeded rather than persisted: Tensor is the one decoder known to fail, and starting
     * there spares those devices ten seconds of black on their first tune. Left unpersisted
     * so a firmware that ever fixes it is not locked out by a note we wrote once.
     */
    private fun storedFieldDecodeVerdict(): FieldDecodeVerdict {
        val stored = context.getSharedPreferences(FieldDecodePrefs, Context.MODE_PRIVATE)
            .getString(FieldDecodeKey, null)
            ?.let { name -> FieldDecodeVerdict.entries.firstOrNull { it.name == name } }
        if (stored != null) return stored
        return if (socCannotDecodeFieldPictures()) {
            FieldDecodeVerdict.HardwareEmitsNothing
        } else {
            FieldDecodeVerdict.Unknown
        }
    }

    /**
     * Whether this device's hardware AVC decoder cannot decode field pictures.
     *
     * The Tensor VPU parses a field-coded sequence header and then reports an error on
     * every field, ~50/s, forever, emitting no frame while the audio plays on. libVLC
     * never recovers: mediacodec_ndk claims interlaced support unconditionally, so the
     * reload-to-software guard behind it is dead code.
     *
     * It is the only decoder known to do this, and the guard used to be unconditional —
     * which cost every other device the hardware path. The Amlogic S4 in a Chromecast HD
     * holds 1080i50 at the panel's own cadence and a fifth of the CPU, where software
     * managed a third of the frame rate at two and a half cores of four.
     */
    private fun socCannotDecodeFieldPictures(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            Build.SOC_MANUFACTURER.equals("Google", ignoreCase = true)

    override fun play() {
        focusResume.forget()
        requestAudioFocus()
        vlc {
            pauseWhenPausable = false
            play()
        }
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
        fieldDecodeProbe?.cancel()
        vlc {
            stopRequested = true
            pauseWhenPausable = false
            stop()
        }
        _state.value = PlayerState.IDLE
        _currentMs.value = 0L
        _durationMs.value = 0L
        _isBuffering.value = false
        _bufferPercent.value = 0f
        focusResume.forget()
        abandonAudioFocus()
    }

    override fun togglePlayPause() {
        focusResume.forget()
        when (playPauseIntent(_state.value)) {
            PlayPauseIntent.Play -> vlc { play() }
            // Not vlcPlayer.pause(): libvlc's is a toggle, so it restarts a stream that
            // is not actually running. pause() carries that guard.
            PlayPauseIntent.Pause -> pauseIfPlaying()
            PlayPauseIntent.None -> {}
        }
    }

    override fun seekTo(ms: Long) {
        val clamped = clampSeekTarget(ms, _durationMs.value)
        _currentMs.value = clamped
        vlc { setTime(clamped) }
    }

    override fun skip(deltaMs: Long) {
        seekTo(clampSeekTarget(_currentMs.value + deltaMs, _durationMs.value))
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, maxVolume)
        _volume.value = clamped
        vlcPlayer.volume = (clamped * 100).toInt()
    }

    override fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        // libvlc-android: set volume to 0 for mute, restore for unmute
        if (muted) {
            vlcPlayer.volume = 0
        } else {
            vlcPlayer.volume = (_volume.value * 100).toInt()
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
            vlcPlayer.volume = 0
        } else if (wasPreloading) {
            attachVoutIfReady()
            vlcPlayer.volume = if (_isMuted.value) 0 else (_volume.value * 100).toInt()
            if (!_isMuted.value) requestAudioFocus()
        }
    }

    override fun volumeUp(step: Float) = setVolume(_volume.value + step)
    override fun volumeDown(step: Float) = setVolume(_volume.value - step)

    override fun setPlaybackSpeed(speed: Float) {
        vlcPlayer.rate = speed
        _playbackSpeed.value = speed
    }

    override fun selectAudioTrack(track: TrackInfo?) {
        if (track == null) vlcPlayer.unselectTrackType(IMedia.Track.Type.Audio)
        else vlcPlayer.selectTrack(track.id)
        _selectedAudioTrack.value = track
        applyAudioOutput()
    }

    override fun setAudioOutputMode(mode: AudioOutputMode) {
        audioOutputMode = mode
        applyAudioOutput()
    }

    private var audioOutputMode = AudioOutputMode.AUTO
    private var appliedAudioDevice: String? = null

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

    private fun sinkPassthroughMask(): Long = encodingMask(
        *audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .flatMap { it.encodings.asIterable() }
            .toIntArray()
    )

    override fun selectSubtitleTrack(track: TrackInfo?) {
        spuDisabled = track == null
        if (track == null) vlcPlayer.unselectTrackType(IMedia.Track.Type.Text)
        else vlcPlayer.selectTrack(track.id)
        _selectedSubtitleTrack.value = track
    }

    override fun setAspectRatio(ratio: AspectRatio) {
        _aspectRatio.value = ratio
    }

    override fun setChapter(chapter: Chapter) {
        val idx = _chapters.value.indexOf(chapter)
        if (idx >= 0) {
            vlcPlayer.chapter = idx
            _currentChapter.value = chapter
        }
    }

    override suspend fun takeScreenshot(path: String) {
        // libvlc-android doesn't have a direct screenshot API
        // Consumers can use the native player for platform-specific approaches
    }

    override fun release() {
        if (released) return
        abandonAudioFocus()
        vlcPlayer.detachViews()
        val media = currentMedia
        currentMedia = null
        vlcThread.execute {
            vlcPlayer.stop()
            media?.release()
            vlcPlayer.release()
            libVLC.release()
        }
        vlcThread.shutdown()
        released = true
        scope.cancel()
    }

    // --- Internal ---

    private fun updateVideoSize() {
        val track = (vlcPlayer.getSelectedTrack(IMedia.Track.Type.Video)
            ?: vlcPlayer.getTracks(IMedia.Track.Type.Video)?.firstOrNull()) as? IMedia.VideoTrack ?: return
        if (track.width > 0 && track.height > 0) {
            _videoSize.value = displaySize(track.width, track.height, track.sarNum, track.sarDen)
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
