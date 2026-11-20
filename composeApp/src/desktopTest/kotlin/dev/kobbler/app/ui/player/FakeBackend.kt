package dev.kobbler.app.ui.player

import dev.kobbler.app.platform.CastBackend
import dev.kobbler.app.platform.CastSender
import dev.kobbler.app.platform.PlaybackProtocol
import dev.kobbler.app.platform.PlaybackTarget
import dev.kobbler.app.platform.PlaybackTargetKind
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeBackend(
    override val protocol: PlaybackProtocol,
    val sender: FakeSender = FakeSender(),
) : CastBackend, CastSender by sender {
    override val targets = MutableStateFlow<List<PlaybackTarget>>(emptyList())
    val connected = CopyOnWriteArrayList<PlaybackTarget>()
    @Volatile var scanning = false

    fun found(vararg names: String) {
        targets.value = names.map { PlaybackTarget("${protocol.name}:$it", it, PlaybackTargetKind.Tv, protocol) }
    }

    override suspend fun scanActively() {
        scanning = true
        try {
            awaitCancellation()
        } finally {
            scanning = false
        }
    }

    override suspend fun connect(target: PlaybackTarget) {
        connected += target
        sender.connect(target.name)
    }
}
