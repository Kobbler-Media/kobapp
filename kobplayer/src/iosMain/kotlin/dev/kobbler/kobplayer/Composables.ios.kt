package dev.kobbler.kobplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberPlayer(config: PlayerConfig): Player {
    val scope = rememberCoroutineScope()
    val player = remember { IosPlayer(scope.coroutineContext, config) }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    return player
}

@Composable
actual fun rememberPlayerFactory(): (PlayerConfig) -> Player {
    val scope = rememberCoroutineScope()
    return remember { { config -> IosPlayer(scope.coroutineContext, config) } }
}

@Composable
actual fun rememberPlayerAsync(config: PlayerConfig): Player? {
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<Player?>(null) }

    LaunchedEffect(config) {
        player = withContext(Dispatchers.Default) { IosPlayer(scope.coroutineContext, config) }
    }

    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    return player
}

@Composable
actual fun VideoSurface(player: Player, modifier: Modifier, overlay: @Composable (Player) -> Unit) {
    val ios = player as IosPlayer
    Box(modifier) {
        UIKitView(
            factory = ios::videoView,
            modifier = Modifier.matchParentSize(),
            properties = UIKitInteropProperties(interactionMode = null),
        )
        overlay(player)
    }
}
