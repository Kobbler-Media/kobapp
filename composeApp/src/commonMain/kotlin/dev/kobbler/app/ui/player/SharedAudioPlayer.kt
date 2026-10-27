package dev.kobbler.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.PlayerConfig
import dev.kobbler.kobplayer.rememberPlayerFactory
import kotlinx.coroutines.launch

/**
 * The app's one audio-only player, beside the one video player. Built on first use and kept
 * after — most sessions never play audio, and a libVLC per press was the alternative.
 */
class SharedAudioPlayer(private val build: (PlayerConfig) -> Player) {
    var current by mutableStateOf<Player?>(null)
        private set

    fun get(): Player = current ?: build(PlayerConfig(audioOnly = true)).also { current = it }

    fun release() {
        current?.release()
        current = null
    }
}

val LocalSharedAudioPlayer = staticCompositionLocalOf {
    SharedAudioPlayer { error("No SharedAudioPlayer is provided here") }
}

@Composable
fun rememberSharedAudioPlayer(): SharedAudioPlayer {
    val factory = rememberPlayerFactory()
    val shared = remember { SharedAudioPlayer(factory) }
    DisposableEffect(shared) { onDispose { shared.release() } }
    return shared
}

/** One of them has the speakers at a time: whichever starts pauses the other. */
@Composable
fun ExclusiveAudio(video: Player?, audio: SharedAudioPlayer) {
    val sound = audio.current
    if (video == null || sound == null) return
    LaunchedEffect(video, sound) {
        launch { sound.isPlaying.collect { if (it && video.isPlaying.value) video.pause() } }
        launch { video.isPlaying.collect { if (it && sound.isPlaying.value) sound.pause() } }
    }
}
