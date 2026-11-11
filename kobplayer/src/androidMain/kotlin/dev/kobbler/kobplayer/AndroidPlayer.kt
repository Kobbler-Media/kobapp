package dev.kobbler.kobplayer

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
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

private const val AudioClockPollMs = 250L
private const val StuckFrames = 12

// A view rebuilt on the next frame has its surface within a few; past this the stream is
// opened without one rather than held up.
private const val FreshSurfaceWaitMs = 1_000L

private val VlcBinding.timeMs: Long? get() = timeUs.takeIf { it >= 0 }?.let { it / 1000 }

/** libVLC 4 through libvlcjni, onto the app's own surfaces. */
internal class AndroidPlayer(
    private val context: Context,
    parentContext: CoroutineContext,
    config: PlayerConfig,
) : VlcPlayer<LibVlcBinding>(parentContext, config) {

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
    // took the app down with an ANR. So libVLC is called from the lane, and only view
    // attach and detach stay on main: they touch the SurfaceHolder.
    val vlcPlayer: MediaPlayer get() = binding.player

    override fun newEngine(): CommandLane.Engine<LibVlcBinding> {
        var runner: Thread? = null
        val thread = Executors.newSingleThreadExecutor { Thread(it, "kobplayer-vlc").apply { isDaemon = true; runner = this } }
        val events = Events()
        val player = MediaPlayer(libVLC, events).also { events.player = it }
        // libvlcjni waits out a release in native code, where nothing can interrupt it: a stuck
        // player is retired if its call ever returns.
        return CommandLane.Engine(
            LibVlcBinding(player),
            thread.asCoroutineDispatcher(),
            stuckIn = { runner?.stackTrace?.take(StuckFrames)?.joinToString("\n") { "at $it" }.orEmpty() },
        ) {
            it.release()
            thread.shutdown()
        }
    }

    private val volumes = LatestWins<Int>(::post) { vlcPlayer.volume = it }

    // Not settled like the refresh: a device chosen after the audio output has opened restarts it.
    private val audioRoute = LatestWins<Unit>(::post) { applyAudioOutput() }

    // libVLC's position report rides on pictures, so with :no-video it comes only every
    // several seconds and an audiobook's elapsed time visibly lurched. The poll is then the
    // only clock: the report runs ahead of what is heard by the output latency, and the two
    // taking turns walked the position back and forth.
    private val audioClock = PolledClock { SystemClock.elapsedRealtime() }

    override fun publishPositionEvents() = !config.audioOnly

    init {
        if (config.audioOnly) scope.launch {
            _state.collectLatest { state ->
                while (state == PlayerState.PLAYING) {
                    delay(AudioClockPollMs)
                    val time = onVlc { timeMs } ?: break
                    _currentMs.value = audioClock.publish(_currentMs.value, time)
                    _positionReported.value = true
                }
            }
        }
    }

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
                    FocusAction.Pause -> super.pause()
                    FocusAction.Duck -> if (!_isMuted.value) volumes.offer((userVolume() * 0.3f).toInt())
                    FocusAction.Resume -> {
                        restoreVolume()
                        resumePlayback()
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
        volumes.offer(if (_isMuted.value) 0 else userVolume())
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
        vlc { if (player.isSeekable) player.setTime(player.time) }
        pictureTimeout?.cancel()
        pictureTimeout = scope.launch {
            delay(PictureReturnTimeoutMs)
            markPictureReturned()
        }
    }

    private fun resumePlayback() {
        super.play()
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
        return onVlc { media?.stats?.displayedPictures?.let { it to SystemClock.elapsedRealtime() } }
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

    private var fieldDecodeProbe: Job? = null
    @Volatile private var hasVideoTrack = false
    private var forceSoftwareDecode = false
    private val fieldDecodeVerdict: FieldDecodeVerdict get() = DeviceFieldDecode.verdict(context)

    init {
        if (!config.audioOnly) DeviceFieldDecode.selfTestIfUnknown(context)
    }

    private inner class Events : MediaPlayer.Listener {
        @Volatile var player: MediaPlayer? = null

        // An abandoned player's events are about a title nobody is watching any more.
        private val current get() = player === vlcPlayer

        override fun onStateChanged(state: Int) {
            if (!current) return
            when (state) {
                MediaPlayer.State.Opening -> onOpening()
                MediaPlayer.State.Playing -> onPlaying()
                MediaPlayer.State.Paused -> onPaused()
                MediaPlayer.State.Error -> onError()
            }
        }

        override fun onMediaStopping(media: IMedia, reason: Int) {
            if (current && reason != MediaPlayer.StoppingReason.Error) onStopping()
        }

        override fun onBufferingChanged(buffering: Float) {
            if (current) onBuffering(buffering)
        }

        override fun onCapabilitiesChanged(oldCapabilities: Int, newCapabilities: Int) {
            if (current) onPausable(newCapabilities and MediaPlayer.Capability.Pause != 0)
        }

        override fun onPositionChanged(time: Long, position: Double) {
            if (current) onPosition(time)
        }

        override fun onLengthChanged(length: Long) {
            if (current) onLength(length)
        }

        override fun onTrackListChanged(action: Int, type: Int, id: String) {
            if (!current || action == MediaPlayer.ListAction.Removed) return
            if (type == IMedia.Track.Type.Audio) audioRoute.offer(Unit)
            if (type == IMedia.Track.Type.Video) hasVideoTrack = true
            onTracksChanged()
        }

        override fun onTrackSelectionChanged(type: Int, unselectedId: String?, selectedId: String?) {
            if (!current) return
            if (type == IMedia.Track.Type.Audio) audioRoute.offer(Unit)
            onTracksChanged()
            if (type == IMedia.Track.Type.Video && selectedId != null) deferredRedraw?.let {
                it.cancel()
                deferredRedraw = null
                redrawPicture()
            }
        }

        override fun onVoutChanged(count: Int) {
            if (current) onTracksChanged()
        }
    }

    override fun onPosition(timeUs: Long) {
        if (awaitingPicture) markPictureReturned()
        super.onPosition(timeUs)
    }

    override fun onLength(lengthUs: Long) {
        super.onLength(lengthUs)
        updateModeSwitchAllowed()
    }

    // --- Commands ---

    override fun load(source: MediaSource) {
        when (source) {
            is MediaSource.Url -> {
                forceSoftwareDecode = false
                open(source)
                armFieldDecodeProbe(source)
            }
        }
    }

    override fun beforeOpen(source: MediaSource.Url) {
        deferredRedraw?.cancel()
        deferredRedraw = null
        hasVideoTrack = false
        // The last title's geometry would size this one's frame until its own tracks report.
        _videoSize.value = 0 to 0
        _decodedPicture.value = null
        _contentFrameRate.value = 0f
        armFrameRateProbe()
        audioClock.reset()
        updateModeSwitchAllowed()
        // Before play(), never after. play() opens the elementary stream, and that
        // is the one moment a decoder is chosen; a window that arrives later is a
        // window MediaCodec was never offered.
        attachVoutIfReady()
        // Only grab audio focus if not preloading (preload should not
        // steal focus from other apps like music players).
        if (!isPreloading.value) requestAudioFocus()
    }

    override fun LibVlcBinding.startTitle(source: MediaSource.Url) = start(media(source))

    private fun media(source: MediaSource.Url): Media {
        return Media(Uri.parse(source.url)).apply {
            vlcMediaOptions(source, config).forEach { addOption(":$it") }
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
        val (clockMs, pictures) = onVlc { (timeMs ?: 0L) to (media?.stats?.displayedPictures ?: 0L) } ?: return
        watch.sample(clockMs, pictures, hasVideoTrack)
    }

    override fun play() {
        focusResume.forget()
        requestAudioFocus()
        resumePlayback()
    }

    override fun pause() {
        focusResume.forget()
        super.pause()
    }

    override fun stop() {
        deferredRedraw?.cancel()
        deferredRedraw = null
        fieldDecodeProbe?.cancel()
        frameRateProbe?.cancel()
        super.stop()
        focusResume.forget()
        abandonAudioFocus()
    }

    override fun togglePlayPause() {
        focusResume.forget()
        when (playPauseIntent(_state.value)) {
            PlayPauseIntent.Play -> resumePlayback()
            PlayPauseIntent.Pause -> super.pause()
            PlayPauseIntent.None -> {}
        }
    }

    override fun onSeek(ms: Long) = audioClock.seeked(ms)

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, maxVolume)
        _volume.value = clamped
        volumes.offer(userVolume())
    }

    override fun setNormalizationGain(gain: Float) {
        normalization = gain
        if (!isPreloading.value) restoreVolume()
    }

    override fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        // libvlc-android: set volume to 0 for mute, restore for unmute
        if (muted) {
            volumes.offer(0)
        } else {
            volumes.offer(userVolume())
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

    override fun onAudioTrackSelected() = applyAudioOutput()

    override fun setAudioOutputMode(mode: AudioOutputMode) {
        audioOutputMode = mode
        audioRoute.offer(Unit)
    }

    @Volatile private var audioOutputMode = AudioOutputMode.AUTO
    private val route = AudioRoute()

    // Chosen per track, because whether a stream can go out as bitstream depends on its
    // codec. Setting a device also stops libVLC's own HDMI listener, which would otherwise
    // pick multichannel PCM for any sink that reports a compressed format.
    private fun applyAudioOutput() {
        val codec = vlcPlayer.getSelectedTrack(IMedia.Track.Type.Audio)?.codec
        val device = audioOutputDevice(audioOutputMode, codec, sinkPassthroughMask())
        route.select(device).forEach(vlcPlayer::setAudioOutputDevice)
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
        rebuildAudioOutput()
    }

    private fun rebuildAudioOutput() {
        post { if (route.invalidate()) audioRoute.offer(Unit) }
    }

    // A TV box that sleeps with the TV can come back with its AudioTrack dead and no sink
    // ever reported gone. Paused, the rebuild lands silently on the next play; playing, it
    // would be an audible gap in sound that evidently survived.
    private val wakeWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (_state.value != PlayerState.PLAYING) rebuildAudioOutput()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(sinkWatcher, Handler(Looper.getMainLooper()))
        context.applicationContext.registerReceiver(wakeWatcher, IntentFilter(Intent.ACTION_SCREEN_ON))
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
        context.applicationContext.unregisterReceiver(wakeWatcher)
        abandonAudioFocus()
        vlcPlayer.detachViews()
        lane.close { libVLC.release() }
    }

    // Main thread, before the load or stop that found the old player stuck runs on the new one.
    // Its display is a layer on the surface that a detach never reaches while it is stuck:
    // shared, it drew bars around the new player's picture. So the new player gets a new
    // surface, and the stuck one's goes with the view that held it.
    override suspend fun adoptFreshPlayer(stuck: LibVlcBinding) = withContext(Dispatchers.Main) {
        val stuckVout = stuck.player.vlcVout
        if (stuckVout.areViewsAttached()) stuckVout.detachViews()
        voutAttached = false
        surfaces.renew(FreshSurfaceWaitMs)
        attachVoutIfReady()
        post { route.reset() }
        audioRoute.offer(Unit)
        restoreVolume()
    }

    // --- Internal ---

    override fun updateVideoSize() {
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
}
