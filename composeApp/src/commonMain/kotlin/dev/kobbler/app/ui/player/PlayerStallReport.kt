package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.CancellationException

/**
 * Report each native player call given up on, and each player that stayed stuck in one.
 *
 * Playback moves to a fresh player within a couple of seconds, so the viewer may see no
 * more than a hitch: without this, a cause that keeps doing it would go unseen.
 */
internal suspend fun GlobalPlayerState.reportStalls(player: Player, api: KobblerApi) {
    player.stalls.collect { stall ->
        if (stall.released == true) return@collect
        val detail = buildString {
            appendLine("Player stuck: ${stall.summary()}")
            appendLine()
            diagnosticContext().forEach { appendLine(it) }
            appendLine("Stuck in:")
            append(stall.stuckIn)
        }
        try {
            api.reportPlaybackFailure(
                detail = detail,
                mediaType = mediaType,
                mediaId = mediaId,
                streamUrl = lastStreamUrl,
                sessionId = liveSessionId ?: sessionId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A diagnostic that cannot be delivered is not worth a second failure.
        }
    }
}
