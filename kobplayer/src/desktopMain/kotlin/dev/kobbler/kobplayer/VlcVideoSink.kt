package dev.kobbler.kobplayer

import androidx.compose.runtime.mutableIntStateOf
import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Rect
import uk.co.caprica.vlcj.binding.internal.libvlc_display_callback_t
import uk.co.caprica.vlcj.binding.internal.libvlc_lock_callback_t
import uk.co.caprica.vlcj.binding.internal.libvlc_unlock_callback_t
import uk.co.caprica.vlcj.binding.internal.libvlc_video_format_cb
import uk.co.caprica.vlcj.binding.internal.libvlc_video_output_cleanup_cb
import uk.co.caprica.vlcj.binding.lib.LibVlc
import uk.co.caprica.vlcj.player.base.MediaPlayer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Raw libvlc callbacks rather than vlcj's CallbackVideoSurface: that one hides the chroma
// the decoder proposes and hands VLC a single buffer, so every frame was converted to RGB
// on the CPU and copied again under the renderer's feet.
internal class VlcVideoSink(
    private val onPicture: (FrameLayout) -> Unit,
) {
    val frameVersion = mutableIntStateOf(0)

    @Volatile
    var renderingEnabled = false

    private val extraFrames = AtomicInteger(0)

    @Volatile
    private var current: Binding? = null

    // A player taken over while stuck can still call back whenever it comes unstuck: its
    // callbacks stay reachable, and its pictures go to frames of its own, until it is released.
    private val bindings = ConcurrentHashMap<MediaPlayer, Binding>()

    private val frames: FrameSet? get() = current?.frames

    @Volatile
    private var blank = false

    private val painter = FramePainter()
    private var shown: FrameSet? = null
    private var shownFrame: DecodedFrame? = null
    private var shownSerial = -1L

    fun attach(mediaPlayer: MediaPlayer) {
        val binding = Binding()
        bindings[mediaPlayer] = binding
        current = binding
        val instance = mediaPlayer.mediaPlayerInstance()
        LibVlc.libvlc_video_set_format_callbacks(instance, binding.format, binding.cleanup)
        LibVlc.libvlc_video_set_callbacks(instance, binding.lock, binding.unlock, binding.display, null)
    }

    fun detach(mediaPlayer: MediaPlayer) {
        bindings.remove(mediaPlayer)
    }

    private inner class Binding {
        @Volatile
        var frames: FrameSet? = null

        val format = libvlc_video_format_cb { _, chroma, width, height, pitches, lines ->
            val proposed = chroma.pointer.getString(0, "US-ASCII").take(4)
            // vmem passes {coded, visible} for each: the track list learns the visible size an
            // instant later, and a sink that asked it got the coded size whenever it won the race.
            val (w, h) = negotiatedSize(width.getInt(0), height.getInt(0), width.getInt(4) to height.getInt(4))
            val layout = FrameLayout(PixelFormat.forDecoderChroma(proposed, SkiaTextures.alpha16 != null), w, h)
            chroma.pointer.write(0, layout.format.fourcc.toByteArray(Charsets.US_ASCII), 0, 4)
            width.setInt(0, w)
            height.setInt(0, h)
            pitches.pointer.write(0, layout.planes.map { it.pitch }.toIntArray(), 0, layout.planes.size)
            lines.pointer.write(0, layout.planes.map { it.lines }.toIntArray(), 0, layout.planes.size)
            frames = FrameSet(layout)
            if (this === current) onPicture(layout)
            VlcPicturePool
        }

        val cleanup = libvlc_video_output_cleanup_cb { frames?.vlcReleased() }

        val lock = libvlc_lock_callback_t { _, planes ->
            val set = frames!!
            val slot = set.mailbox.lock()
            set.layout.planes.forEachIndexed { i, plane ->
                planes.pointer.setPointer(i.toLong() * Native.POINTER_SIZE, set.slots[slot].share(plane.offset))
            }
            Pointer(slot + 1L)
        }

        val unlock = libvlc_unlock_callback_t { _, picture, _ ->
            frames?.mailbox?.unlock(slotOf(picture))
        }

        val display = libvlc_display_callback_t { _, picture ->
            if (this === current && (renderingEnabled || extraFrames.getAndUpdate { if (it > 0) it - 1 else 0 } > 0)) {
                frames?.mailbox?.publish(slotOf(picture))
                blank = false
                frameVersion.intValue++
            }
        }

        init {
            listOf(format, cleanup, lock, unlock, display).forEach {
                Native.setCallbackThreadInitializer(it, CallbackThreadInitializer(true, false, "vlc-video"))
            }
        }
    }

    fun allowFrames(count: Int) = extraFrames.set(count)

    fun clear() {
        blank = true
        frames?.mailbox?.clear()
        frameVersion.intValue++
    }

    fun draw(canvas: Canvas, dst: Rect) {
        val frame = latestFrame() ?: return
        painter.draw(canvas, frame, dst)
    }

    // Render thread only. A new picture format keeps the last picture of the old one on
    // screen until the first picture of the new one lands — no black flash on a variant
    // switch.
    fun latestFrame(): DecodedFrame? {
        if (blank) return dropShown()
        val set = frames ?: return dropShown()
        val slot = set.mailbox.acquire() ?: return if (set === shown) dropShown() else shownFrame
        val serial = set.mailbox.frontSerial
        if (set !== shown) {
            painter.forget()
            shown?.releasedByRenderer()
            set.heldByRenderer()
            shown = set
        } else if (serial == shownSerial) {
            return shownFrame
        }
        shownSerial = serial
        return DecodedFrame(set.layout, set.slots[slot]).also { shownFrame = it }
    }

    private fun dropShown(): DecodedFrame? {
        painter.forget()
        shown?.releasedByRenderer()
        shown = null
        shownFrame = null
        return null
    }

    private fun slotOf(picture: Pointer) = (Pointer.nativeValue(picture) - 1).toInt()

    private class FrameSet(val layout: FrameLayout) {
        val mailbox = FrameMailbox(VlcPicturePool + 2)
        private val allocations = List(VlcPicturePool + 2) { Memory(layout.frameBytes + 63) }
        val slots: List<Pointer> = allocations.map { it.align(64) }

        private var vlcDone = false
        private var rendererHolds = false
        private var closed = false

        @Synchronized
        fun vlcReleased() {
            vlcDone = true
            closeIfUnused()
        }

        @Synchronized
        fun heldByRenderer() {
            rendererHolds = true
        }

        @Synchronized
        fun releasedByRenderer() {
            rendererHolds = false
            closeIfUnused()
        }

        private fun closeIfUnused() {
            if (vlcDone && !rendererHolds && !closed) {
                closed = true
                allocations.forEach(Memory::close)
            }
        }
    }
}
