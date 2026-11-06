package dev.kobbler.app.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    internal var bounds = IntSize.Zero

    val zoomed: Boolean get() = scale > 1f

    /** Scales about [centroid] so the content under it stays put, then pans, never past the edges. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val next = (scale * zoom).coerceIn(1f, MaxScale)
        val change = next / scale
        val fromCenter = centroid - Offset(bounds.width / 2f, bounds.height / 2f)
        scale = next
        offset = clamp(fromCenter * (1 - change) + offset * change + pan)
    }

    fun toggle(at: Offset) = if (zoomed) reset() else transform(at, Offset.Zero, TapScale)

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    private fun clamp(to: Offset): Offset {
        val maxX = bounds.width * (scale - 1) / 2
        val maxY = bounds.height * (scale - 1) / 2
        return Offset(to.x.coerceIn(-maxX, maxX), to.y.coerceIn(-maxY, maxY))
    }

    companion object {
        const val MaxScale = 4f
        const val TapScale = 2.5f
    }
}

@Composable
fun rememberZoomState(): ZoomState = remember { ZoomState() }

/** Pinch, pan once zoomed, and double tap to zoom in or back out; [onTap] gets single taps. */
@Composable
fun Modifier.zoomable(state: ZoomState, onTap: (Offset) -> Unit = {}, onChange: () -> Unit = {}): Modifier {
    val tap by rememberUpdatedState(onTap)
    val change by rememberUpdatedState(onChange)
    val transform = rememberTransformableState { centroid, zoom, pan, _ ->
        state.transform(centroid, pan, zoom)
        change()
    }
    return onSizeChanged { state.bounds = it }
        // Unzoomed drags belong to whatever scrolls underneath: a list, a pager.
        .transformable(transform, canPan = { state.zoomed })
        .pointerInput(state) {
            detectTapGestures(
                onTap = { tap(it) },
                onDoubleTap = {
                    state.toggle(it)
                    change()
                },
            )
        }
}

fun Modifier.zoomed(state: ZoomState): Modifier = graphicsLayer {
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.offset.x
    translationY = state.offset.y
}
