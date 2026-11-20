package dev.kobbler.app.platform

import kotlinx.coroutines.flow.Flow

/** One way of reaching a receiver: what it can find, and the session once connected. */
interface CastBackend : CastSender {
    val protocol: PlaybackProtocol

    /** Discovery runs while this is collected. */
    val targets: Flow<List<PlaybackTarget>>

    /** Look harder while the viewer is choosing. Runs until cancelled. */
    suspend fun scanActively()

    suspend fun connect(target: PlaybackTarget)
}
