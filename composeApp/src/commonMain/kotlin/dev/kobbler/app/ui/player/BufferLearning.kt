package dev.kobbler.app.ui.player

import dev.kobbler.app.data.BufferClass
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.isSeekJump
import dev.kobbler.app.data.isStall
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.PlayerState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

private const val CLEAN_STRETCH_MS = 10 * 60_000L

private val monotonicStart = TimeSource.Monotonic.markNow()

/**
 * Tell the buffer policy what playback ran into: a stream that ran dry mid-play earns its
 * server a deeper buffer from the next start, and a long stretch played without one eases
 * it back. A channel learns apart from the server relaying it. Starts are the start supervisor's to judge, so only buffering out of playing
 * counts, and buffering a seek caused is the seek's.
 */
internal suspend fun GlobalPlayerState.learnBuffering(
    player: Player,
    api: KobblerApi,
    now: () -> Long = { monotonicStart.elapsedNow().inWholeMilliseconds },
): Unit = coroutineScope {
    var lastSeekAt: Long? = null
    launch {
        var previous = player.currentMs.value
        player.currentMs.collect { position ->
            if (isSeekJump(previous, position)) lastSeekAt = now()
            previous = position
        }
    }

    var previousState = player.state.value
    var cleanSince: Long? = null
    launch {
        player.currentMs.collect {
            val since = cleanSince ?: return@collect
            if (player.state.value != PlayerState.PLAYING || now() - since < CLEAN_STRETCH_MS) return@collect
            lastStreamUrl?.let { api.recordCleanSession(it, BufferClass.of(mediaType)) }
            cleanSince = now()
        }
    }
    player.state.collect { state ->
        if (state == PlayerState.PLAYING && cleanSince == null) cleanSince = now()
        // A tuner's picture breaks up with reception, not with the network to the server.
        if (previousState == PlayerState.PLAYING && state == PlayerState.BUFFERING && mediaType != "livetv" &&
            isStall(lastSeekAt?.let { now() - it })
        ) {
            lastStreamUrl?.let { api.recordStall(it, BufferClass.of(mediaType)) }
            cleanSince = null
        }
        previousState = state
    }
}
