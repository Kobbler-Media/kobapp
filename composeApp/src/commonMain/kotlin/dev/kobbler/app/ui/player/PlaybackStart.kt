package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val ORIGIN_PROBE_TIMEOUT_MS = 1_000L

/** A minted session and the player-side offset to open it at. */
data class PlaybackStart(
    val session: KobblerApi.StreamSession,
    val seekToSec: Int,
)

/**
 * Everything a play needs from the server, asked for at once.
 *
 * Minting a session and reading where the viewer left off share no inputs, so asking for
 * the second only once the first has answered spends a whole round trip on nothing. Every
 * play path did that, and each one had its own copy of the sequence, which is how the
 * prewarm came to be fixed and the button behind it did not. This is the only copy.
 *
 * [savedFallback] is the position already on the library object the screen was drawn
 * from — used when the server has no watch state to report, so a resume still lands.
 * [fromStart] skips the saved position entirely, rebase included.
 */
suspend fun startPlayback(
    api: KobblerApi,
    type: String,
    id: String,
    savedFallback: Int? = null,
    fromStart: Boolean = false,
): PlaybackStart? = coroutineScope {
    // The buffer the stream opens with is sized from this, so it is timed alongside the
    // session rather than left to whatever requests happened to come before.
    launch { withTimeoutOrNull(ORIGIN_PROBE_TIMEOUT_MS) { api.probeOrigin() } }
    val progress = if (fromStart) null else async {
        when (type) {
            "movie" -> api.getMovieProgress(id)
            else -> api.getEpisodeProgress(id)
        } ?: savedFallback ?: 0
    }
    // The library object the screen was drawn from already carries a position, so the
    // rebase can be asked for in the same request rather than waiting on the authoritative
    // one — which is then only needed for the residual seek.
    val rebaseTo = if (fromStart) null else resumeRequestSeconds(savedFallback)
    val session = when (type) {
        "movie" -> api.getMovieStreamSession(id, rebaseTo)
        else -> api.getEpisodeStreamSession(id, rebaseTo)
    }
    if (session == null) {
        progress?.cancel()
        return@coroutineScope null
    }
    PlaybackStart(session, progress?.let { resumeSeekSeconds(it.await(), session.serverStartSeconds) } ?: 0)
}
