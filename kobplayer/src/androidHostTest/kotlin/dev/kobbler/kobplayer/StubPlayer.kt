package dev.kobbler.kobplayer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Proxy

class StubPlayer(initial: PlayerState = PlayerState.PLAYING, muted: Boolean = false) {
    val state = MutableStateFlow(initial)
    val direct = mutableListOf<String>()

    val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.name) {
            "getState" -> state
            "getCurrentMs", "getDurationMs" -> MutableStateFlow(0L)
            "getPlaybackSpeed" -> MutableStateFlow(1f)
            "isMuted" -> MutableStateFlow(muted)
            "isBuffering", "isPlaying" -> MutableStateFlow(false)
            "getMaxVolume" -> 1f
            "play", "pause", "togglePlayPause" -> { direct += method.name; Unit }
            else -> if (method.returnType == StateFlow::class.java) MutableStateFlow(null) else null
        }
    } as Player
}
