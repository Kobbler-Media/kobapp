package dev.kobbler.app.ui.player

import androidx.compose.runtime.Immutable
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamResolveResult
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Asking for a download of the title a stream would not play. */
@Immutable
class WantAction(val run: suspend () -> Unit)

/** What the failure dialog can offer beyond the report: other releases, and a download. */
@Immutable
class PlaybackFailureHelp(
    val summary: String,
    val alternatives: List<StreamOption> = emptyList(),
    val onPickAlternative: ((StreamOption) -> Unit)? = null,
    val want: WantAction? = null,
)

private const val NEXT_STREAM_NOTE = "That stream wouldn't play — trying another…"
private const val RECONNECT_STREAM_NOTE = "Lost the stream — reconnecting…"

/**
 * One title's debrid playback, from the first resolve until something plays or nothing will.
 *
 * A resolve answering is not the end of it: the session can still fail to probe, or hand
 * the player something it cannot open. Those used to end in the dialog with the rest of
 * the list untried, so the walk carries on from here instead — the player reports a start
 * that failed, and this resolves the next option where the last one left off.
 */
internal class DebridPlayback(
    private val api: KobblerApi,
    private val gps: GlobalPlayerState,
    private val options: List<StreamOption>,
    private val resolve: suspend (option: StreamOption, resumeSec: Int) -> StreamResolveResult,
    private val play: suspend (option: StreamOption, session: KobblerApi.StreamSession, resumeSec: Int) -> Unit,
    private val onCaching: (WalkResult.Caching) -> Unit,
    private val want: WantAction?,
    private val title: String,
    private val posterUrl: String?,
    private val context: List<String>,
) {
    private val tried = mutableSetOf<String>()
    private val failedSessions = mutableSetOf<String>()
    private val reopenedMidway = mutableSetOf<String>()
    private var current: StreamOption? = null
    private var playedSession: String? = null
    private var retry: Job? = null

    /** Attempts since the viewer last chose: an explicit pick buys a fresh budget. */
    private var walked = 0
    private var resumeSec = 0

    suspend fun start(from: StreamOption?, resumeSec: Int) {
        this.resumeSec = resumeSec
        if (from != null) walked = 0
        val candidates = fallbackCandidates(options, from, skip = tried).take(MAX_AUTO_FALLBACK + 1 - walked)
        val offset = walked
        val result = walkStreamOptions(
            candidates = candidates,
            resolve = { resolve(it, resumeSec) },
            onAttempt = { i -> if (i > 0) fallbackNote(offset + i)?.let { gps.preparingNote = it } },
        )
        tried += result.tried.map { it.id }
        walked += result.tried.size
        when (result) {
            is WalkResult.Ready -> {
                current = result.option
                play(result.option, result.session, resumeSec)
                gps.debridPlayback = this
            }
            is WalkResult.Caching -> onCaching(result)
            is WalkResult.Exhausted -> giveUp(result.lastError)
        }
    }

    fun onPlaying(sessionId: String?) {
        if (sessionId == null || sessionId == playedSession || current == null) return
        playedSession = sessionId
        gps.teardownScope.launch { api.reportStreamOutcome(sessionId, ok = true) }
    }

    /**
     * Take a failure off the player's hands. A start that never played moves on to the
     * next option by itself; one that played and then broke is the viewer's call, so it
     * gets the dialog with the rest of the list in it.
     */
    fun takeOver(player: Player, cause: Throwable): Boolean {
        val sid = gps.sessionId ?: return false
        val lost = current ?: return false
        if (!failedSessions.add(sid)) return true
        resumeSec = resumeSecondsNow(player)
        // A release that played and then dropped is more likely a hiccup — an expired link,
        // a CDN blip — than a bad file, so it is reopened once before anything is blamed.
        val reopen = sid == playedSession && reopenedMidway.add(lost.id)
        gps.teardownScope.launch {
            if (!reopen) api.reportStreamOutcome(sid, ok = false, reason = cause.message)
            api.deleteDebridSession(sid)
        }
        if (sid == playedSession && !reopen) {
            giveUp(cause, lostMidway = true)
            return true
        }
        player.stop()
        gps.isStarting = true
        gps.isPreparing = true
        gps.preparingNote = if (reopen) RECONNECT_STREAM_NOTE else NEXT_STREAM_NOTE
        relaunch {
            val untried = options.any { it.id !in tried }
            when {
                reopen -> start(lost, resumeSec)
                untried && walked < MAX_AUTO_FALLBACK + 1 -> start(null, resumeSec)
                else -> giveUp(cause)
            }
        }
        gps.onPreparingCancel = {
            retry?.cancel()
            gps.stop(player, api, gps.teardownScope)
        }
        return true
    }

    private fun relaunch(block: suspend () -> Unit) {
        retry?.cancel()
        retry = gps.teardownScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                giveUp(e)
            }
        }
    }

    private fun resumeSecondsNow(player: Player): Int =
        (gps.timeline.toAbsoluteMs(player.currentMs.value).coerceAtLeast(gps.pendingSeekMs) / 1000).toInt()

    /** End the walk in the dialog, offering what was not tried and a download. */
    fun giveUp(cause: Throwable?, lostMidway: Boolean = false) {
        val left = options.filter { it.id !in tried }
        gps.failWith(
            cause ?: IllegalStateException("No stream would play"),
            context + "Tried ${tried.size} of ${options.size} stream options",
            help = PlaybackFailureHelp(
                summary = streamFailureSummary(cause, tried.size, lostMidway),
                alternatives = left,
                onPickAlternative = { option ->
                    gps.loadingError = null
                    if (gps.isActive) {
                        gps.isPreparing = true
                        gps.preparingNote = null
                        gps.isFullscreen = true
                    } else {
                        gps.beginPreparing(
                            id = "debrid:${option.id}",
                            newTitle = title,
                            newSubtitle = null,
                            newPosterUrl = posterUrl,
                            onCancel = {
                                retry?.cancel()
                                gps.cancelPreparing()
                            },
                        )
                    }
                    relaunch { start(option, resumeSec) }
                },
                want = want,
            ),
        )
    }
}
