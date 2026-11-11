package dev.kobbler.kobplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal fun withPlayer(
    config: PlayerConfig = PlayerConfig(hardwareAcceleration = false),
    block: suspend (DesktopPlayer) -> Unit,
) {
    DesktopPlayer.prepareLibraries()
    val scope = Dispatchers.Default + SupervisorJob()
    val player = DesktopPlayer(scope, config)
    try {
        runBlocking { withTimeout(20_000) { block(player) } }
    } finally {
        player.release()
        scope.cancel()
    }
}

internal suspend fun <T> StateFlow<T>.await(what: String, predicate: (T) -> Boolean): T =
    runCatching { withTimeout(10_000) { first(predicate) } }.getOrElse { error("$what; last $value") }

internal fun awaitFrame(sink: VlcVideoSink): DecodedFrame? {
    val deadline = System.nanoTime() + 10_000_000_000
    while (System.nanoTime() < deadline) {
        sink.latestFrame()?.let { return it }
        Thread.sleep(20)
    }
    return null
}
