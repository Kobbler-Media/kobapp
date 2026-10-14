package dev.kobbler.kobplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner

// Sized to the picture; the surface inside is grown by the decoder's padding so the padding
// lands past the right and bottom edges, as vlc-android's VideoHelper lays it out.
internal class VideoFrameLayout(context: Context, val surface: SurfaceView) : FrameLayout(context) {

    var picture: DecodedPicture? = null
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    // libVLC drops the video decoder with the surface, and the one rebuilt when the surface
    // returns has nothing until the stream is re-read: the last frame stands in until then.
    private val heldFrame = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        visibility = View.GONE
    }
    private var captured: Bitmap? = null

    // The surface still holds a picture at pause, and is gone by the time it is reported lost.
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE) captureFrame()
    }
    private var observedLifecycle: Lifecycle? = null

    init {
        addView(surface)
        addView(heldFrame)
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
        val bitmap = Bitmap.createBitmap(
            (surface.width / 2).coerceAtLeast(1),
            (surface.height / 2).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        PixelCopy.request(surface, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) captured = bitmap
        }, Handler(Looper.getMainLooper()))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observedLifecycle = findViewTreeLifecycleOwner()?.lifecycle?.also { it.addObserver(lifecycleObserver) }
    }

    override fun onDetachedFromWindow() {
        observedLifecycle?.removeObserver(lifecycleObserver)
        observedLifecycle = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val (surfaceWidth, surfaceHeight) = decodedSurfaceSize(width, height, picture)
        val exactWidth = MeasureSpec.makeMeasureSpec(surfaceWidth, MeasureSpec.EXACTLY)
        val exactHeight = MeasureSpec.makeMeasureSpec(surfaceHeight, MeasureSpec.EXACTLY)
        surface.measure(exactWidth, exactHeight)
        heldFrame.measure(exactWidth, exactHeight)
        // A SurfaceView ignores its parent's bounds; its own clip holds from API 33.
        surface.clipBounds = Rect(0, 0, width, height)
    }
}
