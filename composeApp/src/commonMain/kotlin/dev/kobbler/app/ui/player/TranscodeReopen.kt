package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.kobplayer.Reopened

private const val DIRECT_PLAY = "direct_play"

internal fun transcodeReopen(
    api: KobblerApi,
    session: KobblerApi.StreamSession,
    timeline: PlayerTimeline,
): (suspend (playerMs: Long) -> Reopened?)? {
    if (session.method == null || session.method == DIRECT_PLAY) return null
    return { playerMs ->
        api.seekSession(session.sessionId, timeline.toAbsoluteMs(playerMs) / 1000.0)?.let { seek ->
            // The generation only busts the browser's cached master playlist; the server
            // already serves the rebuilt one.
            val separator = if ('?' in session.url) '&' else '?'
            Reopened(
                url = api.playerUrl("${session.url}${separator}g=${seek.generation}"),
                startMs = timeline.toPlayerMs((seek.startTimeSeconds * 1000).toLong()),
            )
        }
    }
}

internal fun durationHintMs(session: KobblerApi.StreamSession, timeline: PlayerTimeline): Long? =
    session.durationSeconds.takeIf { it > 0 }?.let { timeline.toPlayerMs((it * 1000).toLong()) }
