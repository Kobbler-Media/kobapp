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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

    DisposableEffect(video) {
        video.style.apply {
            position = "fixed"
            zIndex = "0"
            backgroundColor = "black"
        }
        kotlinx.browser.document.body?.appendChild(video)
        onDispose { video.parentElement?.removeChild(video) }
    }

    // The <video> sits under Compose's canvas, so the canvas is cleared to transparent
    // over it and the element is kept on the same rectangle.
    Box(
        modifier
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                val scale = kotlinx.browser.window.devicePixelRatio
                video.style.apply {
                    left = "${bounds.left / scale}px"
                    top = "${bounds.top / scale}px"
                    width = "${bounds.width / scale}px"
                    height = "${bounds.height / scale}px"
                }
            }
            .drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) },
    ) {
        overlay(player)
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
