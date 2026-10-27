package dev.kobbler.kobplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Composable
actual fun rememberPlayer(config: PlayerConfig): Player {
    val scope = rememberCoroutineScope()
    val player = remember {
        DesktopPlayer(scope.coroutineContext, config)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DesktopPlayer.prepareLibraries()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    return player
}

fun createPlayer(config: PlayerConfig, parentContext: kotlin.coroutines.CoroutineContext = Dispatchers.Main): Player {
    return DesktopPlayer(parentContext, config)
}

@Composable
actual fun rememberPlayerFactory(): (PlayerConfig) -> Player {
    val scope = rememberCoroutineScope()
    return remember { { config -> createPlayer(config, scope.coroutineContext) } }
}

@Composable
actual fun VideoSurface(
    player: Player,
    modifier: Modifier,
    overlay: @Composable (Player) -> Unit,
) {
    val video = (player as DesktopPlayer).video
    val ratio by player.aspectRatio.collectAsState()
    val videoSize by player.videoSize.collectAsState()

    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            if (video == null) return@Canvas
            video.frameVersion.intValue
            val (vw, vh) = videoSize
            val (w, h) = videoFrameSize(ratio, vw, vh, size.width.toInt(), size.height.toInt())
            val left = (size.width - w) / 2
            val top = (size.height - h) / 2
            clipRect {
                drawIntoCanvas { video.draw(it.nativeCanvas, Rect.makeXYWH(left, top, w.toFloat(), h.toFloat())) }
            }
        }

        overlay(player)
    }
}

@Composable
actual fun rememberPlayerAsync(config: PlayerConfig): Player? {
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<Player?>(null) }

    LaunchedEffect(config) {
        player = withContext(Dispatchers.IO) {
            DesktopPlayer.prepareLibraries()
            DesktopPlayer(scope.coroutineContext, config)
        }
    }

    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    return player
}
