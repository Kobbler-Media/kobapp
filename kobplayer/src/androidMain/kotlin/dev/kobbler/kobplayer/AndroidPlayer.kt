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
import kotlin.coroutines.CoroutineContext

private const val FieldDecodePrefs = "kobplayer.decoder"
private const val FieldDecodeKey = "field_picture_verdict"

// Often enough to settle a good device quickly, rarely enough to cost nothing.
private const val FieldDecodeProbePollMs = 500L

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
        if (config.verboseLogging) {
            add("-vv")
        }
        if (!config.hardwareAcceleration) {
            add("--avcodec-hw=none")
        }
    })

    val vlcPlayer = MediaPlayer(libVLC)

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

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (config.audioOnly) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        vlcPlayer.pause()
                        hasAudioFocus = false
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        if (_state.value == PlayerState.PLAYING) vlcPlayer.pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        if (!_isMuted.value && !isPreloading.value) {
                            vlcPlayer.volume = ((_volume.value * 100) * 0.3f).toInt()
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        // Only restore volume and resume if not preloading.
                        // Without this check, a phone call ending or alarm stopping
                        // would resume preloaded content on detail pages.
                        if (!isPreloading.value && !_isMuted.value) {
                            vlcPlayer.volume = (_volume.value * 100).toInt()
                            if (_state.value == PlayerState.PAUSED) vlcPlayer.play()
                        }
                        hasAudioFocus = true
                    }
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
    internal var pendingSurface: android.view.SurfaceHolder? = null
    internal var voutAttached = false

    private val _videoOutputReady = MutableStateFlow(false)
    override val videoOutputReady: StateFlow<Boolean> = _videoOutputReady.asStateFlow()

    internal fun onSurfaceReady(ready: Boolean) { _videoOutputReady.value = ready }

    // surfaceChanged can land before the vout is attached — during muted preload the
    // attach is deferred to setMuted(false), by which point the only callback carrying
    // the real dimensions has already been and gone. Bank them so the attach can apply
    // them; without it VLC keeps a stale window size and renders the frame scaled into
    // the corner of the surface.
    internal var pendingSurfaceWidth = 0
    internal var pendingSurfaceHeight = 0

    /** True when the player is silently preloading (decode without render). */
    internal val isPreloading = MutableStateFlow(false)

    // libVLC 3 misses the crop of Codec2 decoders that pad their output — MediaTek hands a
    // 1280x528 film over as 1920x1088 — and places the picture by that padded shape: stretched
    // in a full-screen surface, pillarboxed in one sized to the film. The surface is already
    // sized to the true picture, so VLC is told to draw exactly that shape.
    internal fun onSurfaceSize(width: Int, height: Int) {
        pendingSurfaceWidth = width
        pendingSurfaceHeight = height
        vlcPlayer.aspectRatio = "$width:$height"
        if (voutAttached) vlcPlayer.vlcVout.setWindowSize(width, height)
    }

    internal fun attachVoutIfReady() {
        val holder = pendingSurface ?: return
        if (voutAttached) return
        val vout = vlcPlayer.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
        vout.setVideoSurface(holder.surface, holder)
        vout.attachViews()
        voutAttached = true
        if (pendingSurfaceWidth > 0 && pendingSurfaceHeight > 0) {
            vout.setWindowSize(pendingSurfaceWidth, pendingSurfaceHeight)
        }
    }

    internal fun detachVout() {
        val vout = vlcPlayer.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
        voutAttached = false
    }

    // --- Internal ---

    private var currentMedia: Media? = null

    private val presentedVideo = MutableStateFlow(false)
    private var fieldDecodeProbe: Job? = null
    private var forceSoftwareDecode = false
    private var fieldDecodeVerdict: FieldDecodeVerdict = storedFieldDecodeVerdict()

    init {
        vlcPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> {
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
                    // If we're preloading, intercept: VLC's media.play() auto-starts
                    // playback, and a caller-side pause() racing the state transition
                    // becomes a no-op, leaving position to advance silently.
                    if (isPreloading.value) {
                        vlcPlayer.pause()
                        _state.value = PlayerState.PAUSED
                    } else {
                        _state.value = PlayerState.PLAYING
                    }
                    _isBuffering.value = false
                    reloadTracks()
                    updateVideoSize()
                }
                MediaPlayer.Event.Paused -> {
                    _state.value = PlayerState.PAUSED
                }
                MediaPlayer.Event.EndReached -> {
                    _state.value = PlayerState.FINISHED
                }
                MediaPlayer.Event.EncounteredError -> {
                    _error.value = RuntimeException("VLC playback error")
                    _state.value = PlayerState.ERROR
                }
                MediaPlayer.Event.TimeChanged -> {
                    val time = event.timeChanged
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
        _currentMs.value = 0L
        _durationMs.value = 0L
        _error.value = null

        val headers = source.headers.mapKeys { it.key.lowercase() }

        currentMedia?.release()
        val uri = Uri.parse(source.url)
        val media = Media(libVLC, uri).apply {
            addOption(":http-user-agent=${headers["user-agent"] ?: config.httpUserAgent}")
            addOption(":network-caching=${source.networkCacheMs ?: config.networkCacheMs}")
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
            source.startTimeSec?.let { addOption(":start-time=$it") }
        }
        currentMedia = media
        vlcPlayer.media = media
        // Before play(), never after. play() opens the elementary stream, and that
        // is the one moment a decoder is chosen; a window that arrives later is a
        // window MediaCodec was never offered.
        attachVoutIfReady()
        // Only grab audio focus if not preloading (preload should not
        // steal focus from other apps like music players).
        if (!isPreloading.value) requestAudioFocus()
        vlcPlayer.play()
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
        requestAudioFocus()
        vlcPlayer.play()
    }

    override fun pause() {
        // Only pause if actually playing — libvlc pause() is a toggle
        if (vlcPlayer.isPlaying) vlcPlayer.pause()
    }

    override fun stop() {
        fieldDecodeProbe?.cancel()
        vlcPlayer.stop()
        _state.value = PlayerState.IDLE
        _currentMs.value = 0L
        _durationMs.value = 0L
        _isBuffering.value = false
        _bufferPercent.value = 0f
        abandonAudioFocus()
    }

    override fun togglePlayPause() {
        when (playPauseIntent(_state.value)) {
            PlayPauseIntent.Play -> vlcPlayer.play()
            // Not vlcPlayer.pause(): libvlc's is a toggle, so it restarts a stream that
            // is not actually running. pause() carries that guard.
            PlayPauseIntent.Pause -> pause()
            PlayPauseIntent.None -> {}
        }
    }

    override fun seekTo(ms: Long) {
        val clamped = clampSeekTarget(ms, _durationMs.value)
        _currentMs.value = clamped
        vlcPlayer.time = clamped
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
        if (track == null) {
            vlcPlayer.audioTrack = -1
            _selectedAudioTrack.value = null
        } else {
            val id = track.id.toIntOrNull() ?: return
            vlcPlayer.audioTrack = id
            _selectedAudioTrack.value = track
        }
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
        val selected = vlcPlayer.audioTrack
        val codec = currentMedia?.let { media ->
            (0 until media.trackCount)
                .mapNotNull { media.getTrack(it) as? IMedia.AudioTrack }
                .firstOrNull { it.id == selected }?.codec
        }
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
        if (track == null) {
            spuDisabled = true
            vlcPlayer.spuTrack = -1
            _selectedSubtitleTrack.value = null
        } else {
            val id = track.id.toIntOrNull() ?: return
            spuDisabled = false
            vlcPlayer.spuTrack = id
            _selectedSubtitleTrack.value = track
        }
    }

    override fun setAspectRatio(ratio: AspectRatio) {
        _aspectRatio.value = ratio
        // Aspect ratio is handled at the Compose layout level (Composables.android.kt)
        // rather than via VLC APIs, since libvlc 3.x has limited scaling support.
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

    private var released = false

    override fun release() {
        if (released) return
        released = true
        abandonAudioFocus()
        vlcPlayer.stop()
        vlcPlayer.detachViews()
        currentMedia?.release()
        currentMedia = null
        vlcPlayer.release()
        libVLC.release()
        scope.cancel()
    }

    // --- Internal ---

    private fun updateVideoSize() {
        val media = currentMedia ?: return
        val track = (0 until media.trackCount)
            .firstNotNullOfOrNull { media.getTrack(it) as? IMedia.VideoTrack } ?: return
        if (track.width > 0 && track.height > 0) {
            _videoSize.value = displaySize(track.width, track.height, track.sarNum, track.sarDen)
        }
    }

    private fun reloadTracks() {
        // Audio tracks
        val audioDescs = vlcPlayer.audioTracks ?: emptyArray()
        _audioTracks.value = audioDescs
            .filter { it.id != -1 }
            .map { TrackInfo(it.id.toString(), it.name ?: "Track ${it.id}") }

        val currentAudio = vlcPlayer.audioTrack
        _selectedAudioTrack.value = _audioTracks.value.find { it.id == currentAudio.toString() }

        // Subtitle tracks
        val subDescs = vlcPlayer.spuTracks ?: emptyArray()
        _subtitleTracks.value = subDescs
            .filter { it.id != -1 }
            .map { TrackInfo(it.id.toString(), it.name ?: "Track ${it.id}") }

        // VLC picks a default SPU track whenever the elementary streams are parsed, which
        // lands after the app has already asked for none — leave it and its rendering
        // stacks on top of the server-VTT overlay.
        if (spuDisabled && vlcPlayer.spuTrack != -1) vlcPlayer.spuTrack = -1
        val currentSub = if (spuDisabled) -1 else vlcPlayer.spuTrack
        _selectedSubtitleTrack.value = _subtitleTracks.value.find { it.id == currentSub.toString() }
    }
}
