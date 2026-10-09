package dev.kobbler.kobplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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

actual fun createPlayer(config: PlayerConfig, parentContext: kotlin.coroutines.CoroutineContext): Player {
    return WebPlayer(parentContext, config)
}

@Composable
actual fun VideoSurface(
    player: Player,
    modifier: Modifier,
    overlay: @Composable (Player) -> Unit,
) {
    val web = player as WebPlayer

    // The video element needs to be in the DOM behind the Compose canvas.
    // For now, we append it to the document body and position it behind the canvas.
    // The overlay renders as normal Compose content on top.
    DisposableEffect(web.videoElement) {
        val container = kotlinx.browser.document.getElementById("ComposeTarget")
            ?: kotlinx.browser.document.body!!
        // Insert video before the canvas so it renders behind it
        val canvas = container.querySelector("canvas")
        if (canvas != null) {
            container.insertBefore(web.videoElement, canvas)
        } else {
            container.appendChild(web.videoElement)
        }

        // Style the video element to fill the container
        web.videoElement.style.apply {
            position = "absolute"
            top = "0"
            left = "0"
            width = "100%"
            height = "100%"
            zIndex = "-1"
        }

        onDispose {
            web.videoElement.parentElement?.removeChild(web.videoElement)
        }
    }

    // The overlay composable renders on the Compose canvas layer (above the video)
    Box(modifier) {
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
