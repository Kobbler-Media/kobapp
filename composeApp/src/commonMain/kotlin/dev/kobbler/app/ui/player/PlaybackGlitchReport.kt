package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.PlaybackLifecycle
import dev.kobbler.app.platform.recentPlayerLog
import dev.kobbler.kobplayer.PlaybackGlitch
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.CancellationException

/**
 * Report what went wrong on screen while playback carried on: nothing errors, so a viewer's
 * "the picture froze" is otherwise all there is to go on. Once per kind per title, as a
 * picture that stays stuck would otherwise be found again every few seconds.
 */
internal suspend fun GlobalPlayerState.reportGlitches(player: Player, api: KobblerApi) {
    val reported = mutableSetOf<Pair<String?, PlaybackGlitch.Kind>>()
    player.glitches.collect { glitch ->
        if (!reported.add(mediaId to glitch.kind)) return@collect
        val detail = buildString {
            appendLine("Playback glitch: ${glitch.summary()}")
            appendLine()
            diagnosticContext().forEach { appendLine(it) }
            appendLine("Shown: ${if (PlaybackLifecycle.isInPipMode()) "picture-in-picture" else "in app"}")
            appendLine("Lately:")
            glitch.trail.forEach { appendLine("  $it") }
            val log = recentPlayerLog()
            if (log.isNotEmpty()) {
                appendLine("Player log:")
                log.forEach { appendLine("  $it") }
            }
        }
        println("[PlaybackGlitch] ${detail.lines().first()}")
        try {
            api.reportPlaybackFailure(
                detail = detail.trimEnd(),
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
