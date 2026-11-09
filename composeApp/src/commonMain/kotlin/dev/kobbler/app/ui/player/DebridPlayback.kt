package dev.kobbler.app.ui.player

import androidx.compose.runtime.Immutable
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamResolveResult
import dev.kobbler.app.data.StreamSearchResult
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
internal const val SWITCHED_SOURCE_NOTE = "Switched to another source"

// A new release is a different encode: its keyframes and any cut differ, so it picks up
// a little before the drop rather than risk skipping what the viewer had not yet seen.
private const val SWITCH_REWIND_SEC = 5

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
    private val search: suspend () -> StreamSearchResult,
    private val resolve: suspend (option: StreamOption, resumeSec: Int) -> StreamResolveResult,
    private val play: suspend (option: StreamOption, session: KobblerApi.StreamSession, resumeSec: Int) -> Unit,
    private val onCaching: (WalkResult.Caching) -> Unit,
    private val want: WantAction?,
    private val title: String,
    private val posterUrl: String?,
    private val context: List<String>,
    private var claim: (suspend () -> InstantStart?)? = null,
    private var local: (suspend () -> StreamOption?)? = null,
) {
    private var found: StreamSearchResult? = null
    private val options: List<StreamOption> get() = found?.options.orEmpty()
    private val tried = mutableSetOf<String>()
    private val failedSessions = mutableSetOf<String>()
    private val reopenedMidway = mutableSetOf<String>()
    private val reloadedStalls = mutableSetOf<String>()
    private var abandoned = false
    private var current: StreamOption? = null
    private var currentSession: KobblerApi.StreamSession? = null
    private var playedSession: String? = null
    private var retry: Job? = null
    private var switchingMidway = false

    /** Spent since the viewer last chose: an explicit pick buys a fresh budget. */
    private var walked = 0
    private var resolved = 0
    private var resumeSec = 0

    // The first automatic start claims a stream warmed for this title, if there is one,
    // else the release already on disk, before anything is searched.
    suspend fun start(from: StreamOption?, resumeSec: Int) {
        this.resumeSec = resumeSec
        if (from == null && (startClaimed(resumeSec) || startLocal(resumeSec))) return
        claim = null
        if (from != null) {
            walked = 0
            resolved = 0
        }
        val result = walkStreamOptions(
            candidates = fallbackCandidates(searched(), from, skip = tried),
            resolve = { resolve(it, resumeSec) },
            attempts = MAX_AUTO_FALLBACK + 1 - walked,
            resolves = MAX_WALK_RESOLVES - resolved,
            onAttemptFailed = { gps.preparingNote = NEXT_STREAM_NOTE },
        )
        tried += result.tried.map { it.id }
        walked += result.attempts
        resolved += result.tried.size
        when (result) {
            is WalkResult.Ready -> {
                current = result.option
                currentSession = result.session
                play(result.option, result.session, resumeSec)
                gps.debridPlayback = this
                if (switchingMidway) {
                    switchingMidway = false
                    gps.flashNote(SWITCHED_SOURCE_NOTE)
                }
            }
            is WalkResult.Caching -> onCaching(result)
            is WalkResult.Exhausted -> giveUp(result.lastError, lostMidway = switchingMidway)
        }
    }

    private suspend fun searched(): List<StreamOption> =
        (found ?: search().also { found = it }).options

    private suspend fun startClaimed(resumeSec: Int): Boolean {
        val instant = claim?.also { claim = null }?.invoke() ?: return false
        tried += instant.option.id
        walked++
        resolved++
        current = instant.option
        currentSession = instant.session
        play(instant.option, instant.session, resumeSec)
        gps.debridPlayback = this
        return true
    }

    private suspend fun startLocal(resumeSec: Int): Boolean {
        val option = local?.also { local = null }?.invoke() ?: return false
        val ready = try {
            resolve(option, resumeSec) as? StreamResolveResult.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false
        tried += option.id
        current = option
        currentSession = ready.session
        play(option, ready.session, resumeSec)
        gps.debridPlayback = this
        return true
    }

    /** Pick the walk up again once [option] has finished caching. */
    fun resumeCached(option: StreamOption, resumeSec: suspend () -> Int) = relaunch { start(option, resumeSec()) }

    fun onPlaying(sessionId: String?) {
        if (sessionId == null || sessionId == playedSession || current == null) return
        playedSession = sessionId
        gps.teardownScope.launch { api.reportStreamOutcome(sessionId, ok = true) }
    }

    /**
     * Take a failure off the player's hands. A start that never played moves on to the
     * next option by itself; one that played and then broke is reopened once, and if it
     * breaks again playback moves to the next release where it left off. The dialog is
     * only for when nothing is left to try.
     */
    fun takeOver(player: Player, cause: Throwable): Boolean {
        // Null between a session being retired and the next one loading: whatever the
        // player says about the stream it was just told to drop is not news.
        val sid = gps.sessionId ?: return retry?.isActive == true
        val lost = current ?: return false
        if (sid in failedSessions) return true
        resumeSec = resumeSecondsNow(player)
        if (reloadStalled(player, sid, cause)) return true
        failedSessions += sid
        val midway = sid == playedSession
        // A release that played and then dropped is more likely a hiccup — an expired link,
        // a CDN blip — than a bad file, so it is reopened once before anything is blamed.
        val reopen = midway && reopenedMidway.add(lost.id)
        gps.teardownScope.launch {
            if (!reopen) api.reportStreamOutcome(sid, ok = false, reason = cause.message)
            api.deleteDebridSession(sid)
        }
        retireSession()
        if (midway && !reopen) {
            switchingMidway = true
            resumeSec = (resumeSec - SWITCH_REWIND_SEC).coerceAtLeast(0)
        }
        player.stop()
        gps.isStarting = true
        gps.isPreparing = true
        gps.preparingNote = if (midway) RECONNECT_STREAM_NOTE else NEXT_STREAM_NOTE
        relaunch {
            val untried = searched().any { it.id !in tried }
            when {
                reopen -> start(lost, resumeSec)
                untried && walked < MAX_AUTO_FALLBACK + 1 && resolved < MAX_WALK_RESOLVES -> start(null, resumeSec)
                else -> giveUp(cause, lostMidway = midway)
            }
        }
        gps.onPreparingCancel = {
            retry?.cancel()
            gps.stop(player, api, gps.teardownScope)
        }
        return true
    }

    /**
     * A start that stalled while the server still had the file coming is the player's
     * connection, not the release: open the same session again, once, before blaming it.
     */
    private fun reloadStalled(player: Player, sid: String, cause: Throwable): Boolean {
        if (cause !is StartStall || !cause.sourceHealthy || sid == playedSession) return false
        val session = currentSession?.takeIf { it.sessionId == sid } ?: return false
        val type = gps.mediaType ?: return false
        val id = gps.mediaId ?: return false
        if (!reloadedStalls.add(sid)) return false
        gps.noteStartEvent("stalled with the source still coming: reopening $sid")
        relaunch {
            gps.loadInPlace(player, api, type, id, session, resumeSec)
            gps.playUnmuted(player)
        }
        return true
    }

    /** The session was deleted on the server; nothing may keep asking after it. */
    private fun retireSession() {
        gps.sessionId = null
        gps.thumbnailsUrl = null
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

    /** The viewer left: nothing this walk finds out afterwards is theirs to see. */
    fun abandon() {
        abandoned = true
        retry?.cancel()
    }

    /** End the walk in the dialog, offering what was not tried and a download. */
    fun giveUp(cause: Throwable?, lostMidway: Boolean = false) {
        if (abandoned) return
        switchingMidway = false
        val left = options.filter { it.id !in tried }
        gps.failWith(
            cause ?: IllegalStateException("No stream would play"),
            context + found?.errors.orEmpty() + "Tried ${tried.size} of ${options.size} stream options",
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
