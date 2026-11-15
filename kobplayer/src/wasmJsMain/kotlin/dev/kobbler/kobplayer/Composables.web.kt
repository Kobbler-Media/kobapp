package dev.kobbler.kobplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import org.w3c.dom.HTMLVideoElement
import kotlin.math.roundToInt

@Composable
actual fun rememberPlayer(config: PlayerConfig): Player {
    val scope = rememberCoroutineScope()
    val player = remember {
        WebPlayer(scope.coroutineContext, config)
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    return player
}

@Composable
actual fun rememberPlayerFactory(): (PlayerConfig) -> Player {
    val scope = rememberCoroutineScope()
    return remember { { config -> WebPlayer(scope.coroutineContext, config) } }
}

@Composable
actual fun VideoSurface(
    player: Player,
    modifier: Modifier,
    overlay: @Composable (Player) -> Unit,
) {
    val video = (player as WebPlayer).videoElement
    val ratio by player.aspectRatio.collectAsState()
    val videoSize by player.videoSize.collectAsState()
    val crop by player.pictureCrop.collectAsState()
    var bounds by remember { mutableStateOf(Rect.Zero) }

    DisposableEffect(video) {
        video.style.apply {
            position = "fixed"
            zIndex = "0"
            backgroundColor = "black"
        }
        kotlinx.browser.document.body?.appendChild(video)
        onDispose { video.parentElement?.removeChild(video) }
    }

    LaunchedEffect(video, bounds, ratio, videoSize, crop) {
        video.place(bounds, ratio, videoSize, crop, kotlinx.browser.window.devicePixelRatio)
    }

    // The <video> sits under Compose's canvas, so the canvas is cleared to transparent
    // over it and the element is kept on the same rectangle.
    Box(
        modifier
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) },
    ) {
        overlay(player)
    }
}

// Trimming lays the whole frame out past the surface and clips it back, since object-fit
// can only scale the frame as a whole.
private fun HTMLVideoElement.place(
    bounds: Rect,
    ratio: AspectRatio,
    videoSize: Pair<Int, Int>,
    crop: PictureCrop?,
    scale: Double,
) {
    val (videoWidth, videoHeight) = videoSize
    val trimmed = crop != null && videoWidth > 0 && videoHeight > 0
    val width = bounds.width.roundToInt()
    val height = bounds.height.roundToInt()
    val frame = if (trimmed) videoFrameRect(ratio, videoWidth, videoHeight, width, height, crop) else FrameRect(0, 0, width, height)
    val shown = frame.visibleIn(width, height)
    fun px(value: Number) = "${value.toDouble() / scale}px"
    style.apply {
        left = px(bounds.left + frame.left)
        top = px(bounds.top + frame.top)
        this.width = px(frame.width)
        this.height = px(frame.height)
        objectFit = when {
            trimmed -> "fill"
            ratio == AspectRatio.FIT -> "contain"
            ratio == AspectRatio.STRETCH -> "fill"
            else -> "cover"
        }
        setProperty(
            "clip-path",
            "inset(${px(shown.top)} ${px(frame.width - shown.left - shown.width)} " +
                "${px(frame.height - shown.top - shown.height)} ${px(shown.left)})",
        )
    }
}

@Composable
actual fun rememberPlayerAsync(config: PlayerConfig): Player? {
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<Player?>(null) }

    LaunchedEffect(config) { player = WebPlayer(scope.coroutineContext, config) }

    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    return player
}
