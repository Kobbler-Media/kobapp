package dev.kobbler.kobplayer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner

// Fills the container and places the frame inside it, clipped to what the container shows: a
// trimmed or cropped frame overhangs it. The surface is grown by the decoder's padding so the
// padding lands past the frame's right and bottom edges, as vlc-android's VideoHelper lays it out.
internal class VideoFrameLayout(context: Context, val surface: SurfaceView) : FrameLayout(context) {

    val subtitles = SurfaceView(context).apply {
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        if (Build.VERSION.SDK_INT >= 34) setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
    }

    var picture: DecodedPicture? = null
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var aspectRatio: AspectRatio = AspectRatio.FIT
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var videoSize: Pair<Int, Int> = 0 to 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var crop: PictureCrop? = null
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private var frame = FrameRect(0, 0, 0, 0)

    // Left to guess, SurfaceFlinger reads 24fps through VLC's uneven queueing as 60Hz content
    // in busy scenes and the panel drops to 60, where frames alternate two and three vsyncs.
    // VLC's own SurfaceControl beneath this surface inherits the vote.
    var frameRate: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            applyFrameRate()
        }

    // ALWAYS still defers to the viewer's "Match content frame rate" setting: with it off, or on
    // seamless-only (every phone's default), the panel never blanks. ONLY_IF_SEAMLESS overrode an
    // explicit "Always" and left a TV that cannot switch seamlessly judder 24p at 60Hz.
    var modeSwitchAllowed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyFrameRate()
        }

    var matchDisplayRefresh: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyDisplayMode()
        }

    // A frame-rate vote never takes the panel off the rate the system picked, which on a
    // Pixel playing video is 60Hz: 24p stays 3:2 pulldown. Asking for the display mode does.
    private var requestedModeId = 0

    private fun applyDisplayMode() {
        val window = context.findActivity()?.window ?: return
        val wanted = if (matchDisplayRefresh && isAttachedToWindow) {
            requestableMode() ?: requestedModeId
        } else {
            0
        }
        if (wanted == requestedModeId && window.attributes.preferredDisplayModeId == wanted) return
        requestedModeId = wanted
        window.attributes = window.attributes.apply { preferredDisplayModeId = wanted }
    }

    // Held between episodes, while the next one's duration and rate are still unknown, so a TV
    // does not blank twice to come back to the mode it was already in.
    private fun requestableMode(): Int? {
        if (!modeSwitchAllowed) return null
        val display = display ?: return null
        fun Display.Mode.described() = DisplayMode(modeId, physicalWidth, physicalHeight, refreshRate)
        return displayModeFor(display.supportedModes.map { it.described() }, display.mode.described(), frameRate)
    }

    fun applyFrameRate() {
        applyDisplayMode()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val target = surface.holder.surface
        if (!target.isValid) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val strategy = if (modeSwitchAllowed) Surface.CHANGE_FRAME_RATE_ALWAYS else Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
            target.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, strategy)
        } else {
            target.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
    }

    // libVLC drops the video decoder with the surface, and the one rebuilt when the surface
    // returns has nothing until the stream is re-read: the last frame stands in until then.
    private val heldFrame = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        visibility = View.GONE
    }
    private var captured: Bitmap? = null
    private var frameBuffer: Bitmap? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // The surface still holds a picture at pause, and is gone by the time it is reported lost.
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE) captureFrame()
    }
    private var observedLifecycle: Lifecycle? = null

    init {
        addView(surface)
        addView(heldFrame)
        addView(subtitles)
    }

    fun holdFrame() {
        val frame = captured ?: return
        heldFrame.setImageBitmap(frame)
        heldFrame.visibility = View.VISIBLE
    }

    fun releaseFrame() {
        heldFrame.visibility = View.GONE
        heldFrame.setImageDrawable(null)
        captured = null
    }

    private fun captureFrame() {
        if (!surface.holder.surface.isValid || surface.width <= 0 || surface.height <= 0) return
        val width = (surface.width / 2).coerceAtLeast(1)
        val height = (surface.height / 2).coerceAtLeast(1)
        val bitmap = frameBuffer
            ?.takeIf { it.width == width && it.height == height && heldFrame.visibility != View.VISIBLE }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frameBuffer = it }
        PixelCopy.request(surface, bitmap, { result ->
            captured = bitmap.takeIf { result == PixelCopy.SUCCESS }
        }, mainHandler)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observedLifecycle = findViewTreeLifecycleOwner()?.lifecycle?.also { it.addObserver(lifecycleObserver) }
        applyDisplayMode()
    }

    override fun onDetachedFromWindow() {
        observedLifecycle?.removeObserver(lifecycleObserver)
        observedLifecycle = null
        super.onDetachedFromWindow()
        applyDisplayMode()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        frame = videoFrameRect(aspectRatio, videoSize.first, videoSize.second, width, height, crop)
        val (surfaceWidth, surfaceHeight) = decodedSurfaceSize(frame.width, frame.height, picture)
        val exactWidth = MeasureSpec.makeMeasureSpec(surfaceWidth, MeasureSpec.EXACTLY)
        val exactHeight = MeasureSpec.makeMeasureSpec(surfaceHeight, MeasureSpec.EXACTLY)
        surface.measure(exactWidth, exactHeight)
        heldFrame.measure(exactWidth, exactHeight)
        subtitles.measure(exactWidth, exactHeight)
        // A SurfaceView ignores its parent's bounds; its own clip holds from API 33.
        val shown = frame.visibleIn(width, height)
        val clip = Rect(shown.left, shown.top, shown.left + shown.width, shown.top + shown.height)
        surface.clipBounds = clip
        subtitles.clipBounds = clip
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layout(frame.left, frame.top, frame.left + child.measuredWidth, frame.top + child.measuredHeight)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
