@file:OptIn(ExperimentalForeignApi::class)

package dev.kobbler.kobplayer

import cnames.structs.libvlc_instance_t
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libvlc.libvlc_media_player_new
import libvlc.libvlc_new
import libvlc.libvlc_release
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import kotlin.coroutines.CoroutineContext

/** libVLC 4 through its C API, drawing into a UIView of ours. */
internal class IosPlayer(
    parentContext: CoroutineContext,
    config: PlayerConfig,
) : VlcPlayer<IosVlcBinding>(parentContext, config) {

    private val instance: CPointer<libvlc_instance_t> = memScoped {
        val options = config.readAhead.vlcOptions + if (config.verboseLogging) listOf("-vv") else emptyList()
        libvlc_new(options.size, allocArrayOf(options.map { it.cstr.ptr }))
    } ?: error("libVLC did not start")

    override val native: NativePlayer get() = NativePlayer.LibVlc(binding.player, instance)

    override fun newEngine(): CommandLane.Engine<IosVlcBinding> {
        val events = Events()
        val opaque = StableRef.create(events)
        val player = libvlc_media_player_new(instance, playerCallbacks, opaque.asCPointer())
            ?: error("libVLC did not make a player")
        val binding = IosVlcBinding(player).also { events.binding = it }
        // A stuck call holds its thread for good; IO has threads to spare for that.
        return CommandLane.Engine(binding, Dispatchers.IO.limitedParallelism(1, "kobplayer-vlc")) {
            it.release()
            opaque.dispose()
        }
    }

    // Only the current player's: one taken over while stuck can still report on the title it was stuck in.
    internal inner class Events {
        var binding: IosVlcBinding? = null
        private val current get() = binding === this@IosPlayer.binding

        fun stateChanged(state: VlcInputState) {
            if (!current) return
            when (state) {
                VlcInputState.Opening -> onOpening()
                VlcInputState.Playing -> onPlaying()
                VlcInputState.Paused -> onPaused()
                VlcInputState.Error -> onError()
                else -> {}
            }
        }

        fun stopping(error: Boolean) {
            if (current && !error) onStopping()
        }

        fun buffering(fraction: Float) {
            if (current) onBuffering(fraction)
        }

        fun pausable(canPause: Boolean) {
            if (current) onPausable(canPause)
        }

        fun position(timeUs: Long) {
            if (current) onPosition(timeUs)
        }

        fun length(lengthUs: Long) {
            if (current) onLength(lengthUs)
        }

        fun tracksChanged() {
            if (current) onTracksChanged()
        }
    }

    // --- Video ---

    private val _videoOutputReady = MutableStateFlow(config.audioOnly)
    override val videoOutputReady: StateFlow<Boolean> = _videoOutputReady.asStateFlow()

    private var container: UIView? = null

    /** The view the picture goes into, made on first use. Main thread. */
    internal fun videoView(): UIView = container ?: UIView().also { view ->
        view.backgroundColor = UIColor.blackColor
        view.userInteractionEnabled = false
        container = view
        val drawable = drawableIn(view)
        vlc { attach(drawable) }
        _videoOutputReady.value = true
    }

    // One view per player: a stuck one's picture stays in its own, which goes when the player is replaced.
    private fun drawableIn(container: UIView) = UIView(frame = container.bounds).apply {
        autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        userInteractionEnabled = false
        container.addSubview(this)
    }

    override suspend fun adoptFreshPlayer(stuck: IosVlcBinding) {
        withContext(Dispatchers.Main) {
            stuck.drawable?.removeFromSuperview()
            container?.let { binding.attach(drawableIn(it)) }
        }
        val ratio = aspectRatio.value
        vlc { setFit(ratio) }
        volumes.offer(if (_isMuted.value || isPreloading.value) 0 else userVolume())
    }

    override fun updateVideoSize() {
        binding.videoSize()?.let { _videoSize.value = it }
    }

    init {
        scope.launch { aspectRatio.collect { ratio -> vlc { setFit(ratio) } } }
    }

    // --- Audio ---

    private val volumes = LatestWins<Int>(::post) { binding.setVolume(it) }

    private fun restoreVolume() = volumes.offer(if (_isMuted.value) 0 else userVolume())

    override fun setVolume(volume: Float) {
        _volume.value = volume.coerceIn(0f, maxVolume)
        if (!isPreloading.value) restoreVolume()
    }

    override fun setNormalizationGain(gain: Float) {
        normalization = gain
        if (!isPreloading.value) restoreVolume()
    }

    override fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        vlc { setMute(muted) }
    }

    override fun setPreloading(preloading: Boolean) {
        val wasPreloading = isPreloading.value
        isPreloading.value = preloading
        if (preloading) volumes.offer(0) else if (wasPreloading) restoreVolume()
    }

    override fun IosVlcBinding.startTitle(source: MediaSource.Url) {
        start(source.url, vlcMediaOptions(source, config))
        // New media may reset audio state; a muted preload must not leak.
        if (_isMuted.value) setMute(true)
    }

    override suspend fun takeScreenshot(path: String) {}

    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        lane.close { libvlc_release(instance) }
    }
}
