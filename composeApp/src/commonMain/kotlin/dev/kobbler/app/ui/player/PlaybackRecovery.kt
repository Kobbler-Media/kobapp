package dev.kobbler.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.kobbler.app.data.AppForeground
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.SessionFeed
import dev.kobbler.app.data.SessionStatus
import dev.kobbler.app.data.SessionStatusDto
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.PlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * What a start may cost before it is called lost, by how the server said it would deliver.
 *
 * A direct-played local file has nothing to prepare — no process to spin up, no first
 * segment to wait for — so the only thing a spinner over one can mean is that the bytes
 * are not coming. A transcode has real work in front of it, and a tuner has to lock
 * before it has anything at all.
 *
 * These are only the point at which waiting stops; the server is asked throughout, so a
 * failure it already knows about never waits for one of them.
 */
internal const val DIRECT_START_BUDGET_MS = 6_000L
internal const val TRANSCODE_START_BUDGET_MS = 12_000L
internal const val LIVE_START_BUDGET_MS = 15_000L

/**
 * The longest a start may be waited out even while the server says it is still coming.
 *
 * The server enforces its own end to that — it flips a session to `failed` once its stall
 * thresholds pass — so this is only here so that a server which somehow never does cannot
 * hold a spinner up forever.
 */
internal const val START_CEILING_MS = 45_000L

/**
 * How often to ask the server what it makes of a start that has not happened yet.
 *
 * Tight at first, because the failures worth catching fast — a session that is not there,
 * a transcode that died on its first frame — are all knowable from the first answer. Once
 * past that, a stream the server says is still coming is just a wait, and asking about it
 * four times a second for the rest of it buys nothing.
 */
internal const val START_PROBE_INTERVAL_MS = 400L
private const val SLOW_PROBE_AFTER_MS = 2_000L
private const val SLOW_PROBE_INTERVAL_MS = 1_500L

internal fun GlobalPlayerState.startBudgetMs(method: String?): Long = when {
    isLive -> LIVE_START_BUDGET_MS
    method == "direct_play" -> DIRECT_START_BUDGET_MS
    else -> TRANSCODE_START_BUDGET_MS
}

/** How the wait for a stream to start ended. */
internal sealed interface StartOutcome {
    data object Playing : StartOutcome

    /** The server named a fault. [cause] carries what it said. */
    data class Broken(val cause: Throwable) : StartOutcome

    /** Nothing played. [sourceHealthy]: the server still had the file coming when it was called. */
    data class Stalled(val reason: String, val sourceHealthy: Boolean = false) : StartOutcome

    /** The playback this start belonged to already failed, or a newer start replaced it: no verdict is owed. */
    data object Abandoned : StartOutcome
}

/** A start that never played, as the failure handed to whoever takes it over. */
internal class StartStall(reason: String, val sourceHealthy: Boolean) : IllegalStateException(reason)

/**
 * Wait for a stream that was asked to play to actually play, asking the server what it
 * makes of the wait.
 *
 * The player cannot tell a slow start from a dead one: a reaped session answers, with a
 * playlist that never produces a segment, so there is no error for it to report and the
 * absence of playback is the only signal it has. The server, meanwhile, knows the moment
 * the transcode exits or the file goes unreadable — and, for a debrid session, whether
 * bytes are still reaching the player. See [judgeStart] for how that is read.
 */
internal suspend fun GlobalPlayerState.superviseStart(
    player: Player,
    api: KobblerApi,
    probeIntervalMs: Long = START_PROBE_INTERVAL_MS,
): StartOutcome {
    var waited = 0L
    var interval = probeIntervalMs
    var budget = startBudgetMs(sessionMethod)
    var readyAt: Long? = 0L
    var feed: SessionFeed? = null
    val serial = startSerial
    try {
        while (true) {
            if (loadingError != null || startSerial != serial) return StartOutcome.Abandoned
            // The timeout only bounds the wait — a stream that starts reports it at once, so
            // backing this off never delays noticing that it did.
            val playing = withTimeoutOrNull(interval) {
                player.state.first { it == PlayerState.PLAYING }
            } != null
            if (playing) return StartOutcome.Playing
            waited += interval
            if (waited >= SLOW_PROBE_AFTER_MS) interval = maxOf(probeIntervalMs, SLOW_PROBE_INTERVAL_MS)

            var servedGrew = false
            var downloadGrew = false
            val sid = sessionId
            if (sid != null) {
                val status = api.sessionStatus(sid)
                // A session replaced mid-probe answers for the one that went, not this start.
                if (sid != sessionId) continue
                when (status) {
                    // A request that never arrived says nothing about the session, and
                    // reading it as a death is how a reconnect loop starts on a flaky link.
                    is SessionStatus.Unknown -> {}
                    is SessionStatus.Missing -> return StartOutcome.Broken(
                        IllegalStateException("The server has no record of this playback session"),
                    )
                    is SessionStatusDto -> {
                        if (status.state == "failed") {
                            return StartOutcome.Broken(
                                IllegalStateException(
                                    status.message ?: status.error ?: "The server gave up on this stream",
                                ),
                            )
                        }
                        budget = startBudgetMs(status.method ?: sessionMethod)
                        readyAt = if (status.state == "preparing") null else readyAt ?: waited
                        status.feed?.let { now ->
                            val before = feed
                            servedGrew = before != null && now.bytesServed > before.bytesServed
                            downloadGrew = before != null && (now.downloadedBytes ?: 0) > (before.downloadedBytes ?: 0)
                            feed = now
                        }
                    }
                }
            }
            if (player.state.value == PlayerState.PLAYING) return StartOutcome.Playing
            if (loadingError != null || startSerial != serial) return StartOutcome.Abandoned
            startProgress = if (waited >= FEED_NOTE_AFTER_MS) startFeedLine(feed) else null
            val probe = StartProbe(waited, readyAt?.let { waited - it }, budget, feed, servedGrew, downloadGrew)
            when (val verdict = judgeStart(probe)) {
                StartVerdict.Wait -> {}
                is StartVerdict.Stalled -> return StartOutcome.Stalled(verdict.reason, verdict.sourceHealthy)
            }
        }
    } finally {
        startProgress = null
    }
}

/** How often to ask whether the session behind a paused player still exists. */
private const val SESSION_HEARTBEAT_MS = 120_000L

/**
 * How often to touch a session a detail page has warmed but not played yet. Well inside
 * the server's idle sweep, which is what makes it a touch rather than a poll.
 */
private const val PREWARM_TOUCH_INTERVAL_MS = 90_000L

private const val RECONNECTING_NOTE = "Lost the stream — reconnecting…"

/**
 * How long a reconnect is allowed to take before it is worth mentioning.
 *
 * Most of them land inside a second — a fresh session, the stream reopened where the
 * viewer was — and announcing that is worse than saying nothing: it turns a hiccup into
 * an incident, and it does it over the picture. Past this, silence stops being reassuring
 * and starts being a spinner with no explanation.
 */
internal const val RECONNECT_NOTICE_DELAY_MS = 1_800L

/**
 * How long to wait before each attempt. A session lost to a restarted API cannot be minted
 * again until the server is back up, so the budget is a schedule rather than a count. It
 * spans about forty seconds: a deploy's restart is back serving in around twenty.
 */
internal val RECOVERY_BACKOFFS_MS = listOf(0L, 3_000L, 6_000L, 12_000L, 20_000L)

/**
 * Minting a session succeeds even when what it points at is unplayable, and a fresh
 * session resets every per-stream retry counter there is — so a title that fails the same
 * way each time would reconnect for as long as the viewer left it up.
 */
internal val RECOVERY_WINDOW = 90_000.milliseconds
internal const val MAX_RECOVERIES_IN_WINDOW = 4

private val RecoverableMediaTypes = setOf("movie", "episode")

/**
 * Can this playback be rebuilt from its media id alone?
 *
 * A local movie or episode is one POST away from having a session again, and a channel is
 * one tune. A debrid stream comes straight from the provider with no local session to
 * mint — its way out is a different source, which only the picker can offer.
 */
val GlobalPlayerState.canRecoverSession: Boolean
    get() = when {
        isDebrid -> false
        mediaId == null -> false
        mediaType == "livetv" || mediaType == "iptv" -> true
        else -> !isLive && mediaType in RecoverableMediaTypes
    }

/**
 * One collector for everything that says playback is not happening.
 *
 * Both inputs answer the same way — mint a fresh session and reopen the stream where the
 * viewer was — and both used to reach either nothing at all or straight to a dialog.
 */
suspend fun GlobalPlayerState.watchPlayback(player: Player, api: KobblerApi) = coroutineScope {
    launch {
        // Not collectLatest: a reconnect asks for the next start from inside the
        // supervision of the one it is replacing, and cancelling itself mid-reconnect is
        // exactly the hang this whole path exists to remove. The request is buffered and
        // picked up on the next turn instead.
        startRequests.collect {
            when (val outcome = superviseStart(player, api)) {
                StartOutcome.Playing -> {
                    PlaybackTrace.end()
                    debridPlayback?.onPlaying(sessionId)
                }
                StartOutcome.Abandoned -> {}
                is StartOutcome.Broken -> handleLostStart(player, api, outcome.cause)
                is StartOutcome.Stalled -> handleLostStart(
                    player, api, StartStall(outcome.reason, outcome.sourceHealthy),
                )
            }
        }
    }
    launch {
        // Reconnects ask for a start of their own; one slow start is still one report.
        var slowStartWatch: Job? = null
        startRequests.collect {
            if (slowStartWatch?.isActive != true) slowStartWatch = launch { watchSlowStart(player, api) }
        }
    }
    launch { learnBuffering(player, api) }
    launch {
        player.error.collect { throwable ->
            if (throwable == null || loadingError != null || isRecovering) return@collect
            if (debridPlayback?.takeOver(player, throwable) == true) return@collect
            if (!recoverPlayback(player, api, cause = throwable)) reportPlayerFailure(throwable)
        }
    }
}

private suspend fun GlobalPlayerState.handleLostStart(
    player: Player,
    api: KobblerApi,
    cause: Throwable,
) {
    PlaybackTrace.end()
    if (debridPlayback?.takeOver(player, cause) == true) return
    if (!recoverPlayback(player, api, cause = cause)) failWith(cause, diagnosticContext())
}

/**
 * Ask the server whether the session is still there.
 *
 * A player left paused finds out it has been orphaned only when the viewer presses play,
 * by which time the position it would have resumed from is the position it is stuck at.
 * Nothing else notices: an idle direct-play stream makes no requests to fail.
 */
@Composable
fun PlaybackSessionHeartbeat(
    gps: GlobalPlayerState,
    player: Player,
    api: KobblerApi,
    intervalMs: Long = SESSION_HEARTBEAT_MS,
) {
    val sid = gps.sessionId
    LaunchedEffect(sid) {
        if (sid == null) return@LaunchedEffect
        while (true) {
            delay(intervalMs)
            if (gps.isRecovering || gps.sessionId != sid || !gps.canRecoverSession) continue
            if (api.sessionGone(sid)) {
                // Not in this effect: the reconnect replaces the session id it is keyed on,
                // which would cancel it between stopping the player and reopening it.
                gps.teardownScope.launch { gps.recoverPlayback(player, api, keepPaused = !player.isPlaying.value) }
                return@LaunchedEffect
            }
        }
    }
}

/**
 * Hold a prewarmed session open for as long as the page that minted it is on screen.
 *
 * A detail screen mints a session the moment it opens so that pressing Play is not a cold
 * start, and then hands that same session to the player however much later the press
 * comes. Nothing was keeping it alive in between, and nothing was checking it was still
 * there — so a page left open across an API restart handed the player a session that no
 * longer existed, which is the one failure that looks exactly like a slow start.
 *
 * Asking is also the touch: the server marks a session used on every request, so this both
 * keeps the reaper off it and finds out when it has gone. [onLost] is the cue to mint
 * another rather than hand this one over.
 */
@Composable
fun KeepSessionWarm(api: KobblerApi, sessionId: String?, onLost: () -> Unit) {
    LaunchedEffect(sessionId) {
        if (sessionId == null) return@LaunchedEffect
        keepSessionWarm(PREWARM_TOUCH_INTERVAL_MS.milliseconds, AppForeground.state, { api.sessionGone(sessionId) }, onLost)
    }
}

// Backgrounded, the touch waits for the app to return rather than wake the radio; that first
// touch back is also what finds out whether the session outlived the absence.
internal suspend fun keepSessionWarm(
    interval: Duration,
    foreground: StateFlow<Boolean>,
    sessionGone: suspend () -> Boolean,
    onLost: () -> Unit,
) {
    while (true) {
        delay(interval)
        foreground.first { it }
        if (sessionGone()) {
            onLost()
            return
        }
    }
}

/**
 * Mint a fresh session for what is already loaded and reopen the stream on it.
 *
 * Returns false when this playback is not the kind that can be rebuilt, which is the
 * caller's cue to report the failure instead. True means it was handled — reopened, or
 * given up on with [GlobalPlayerState.loadingError] set.
 *
 * [cause] is the failure that prompted this, kept so that giving up reports what actually
 * broke rather than the fact that reconnecting did not fix it. [keepPaused] reopens the
 * stream without starting it, for a session that died under a player the viewer had
 * already paused — rebuilding the pipeline must not decide to play on their behalf.
 */
suspend fun GlobalPlayerState.recoverPlayback(
    player: Player,
    api: KobblerApi,
    cause: Throwable? = null,
    keepPaused: Boolean = false,
): Boolean {
    val type = mediaType
    val id = mediaId
    if (!canRecoverSession || type == null || id == null) return false
    if (isRecovering) return true
    if (!spendRecoveryBudget()) {
        failWith(cause ?: IllegalStateException("Stream keeps failing — try a different source"), diagnosticContext())
        return true
    }

    isRecovering = true
    isStarting = true
    loadingError = null
    // A reconnect is invisible for its first stretch by design, so this is the only trace
    // of why a start took as long as it did.
    noteStartEvent("reconnect: ${cause?.message ?: "session lost"}")
    // Held back rather than raised: see [RECONNECT_NOTICE_DELAY_MS].
    val notice = teardownScope.launch {
        delay(RECONNECT_NOTICE_DELAY_MS)
        if (!isRecovering) return@launch
        recoveryNotice = true
        isPreparing = true
        preparingNote = RECONNECTING_NOTE
        onPreparingCancel = { stop(player, api, teardownScope) }
    }

    // Absolute, and taken before the player is torn down: a session the server rebased
    // reports a playhead relative to where it was rebased to, and the watch state the
    // server holds lags the real position by up to one progress report.
    val resumeSec = (timeline.toAbsoluteMs(player.currentMs.value).coerceAtLeast(pendingSeekMs) / 1000).toInt()

    // The token is a credential the player cannot renegotiate mid-stream, so a lost
    // stream is a reason to stop trusting it — the server may have been restarted under it.
    api.invalidatePlaybackToken()

    val dead = sessionId
    val deadLive = liveSessionId
    // A restarted API rebuilds a title's session under the same id, so the stream may well
    // still be there — but a session reopened once already and lost again is itself broken.
    var kept = streamSession?.takeIf {
        type in RecoverableMediaTypes && it.sessionId == dead && dead != reopenedSessionId
    }
    player.stop()
    sessionId = null
    liveSessionId = null
    // A channel already playing is answered with that same session, so a close still in
    // flight when the reopen lands ends the session the reopen was just handed.
    if (dead != null && kept == null) {
        if (type == "iptv") api.closeSession(dead) else teardownScope.launch { api.closeSession(dead) }
    }
    // A lease the server still thinks is in use is a tuner the retune cannot have.
    if (deadLive != null) teardownScope.launch { api.closeLiveSession(deadLive) }

    var last: Throwable? = cause
    try {
        for (wait in RECOVERY_BACKOFFS_MS) {
            if (wait > 0) delay(wait)
            // A stop while this was waiting means nobody is watching any more.
            if (mediaId != id) return true
            kept?.let { session ->
                val status = api.sessionStatus(session.sessionId)
                if (status is SessionStatus.Unknown) {
                    last = IllegalStateException("The server could not be reached")
                    return@let
                }
                kept = null
                if (status is SessionStatusDto && status.state != "failed") {
                    reopenedSessionId = session.sessionId
                    loadInPlace(player, api, type, id, session, resumeSec)
                    if (keepPaused) endPreload(player) else playUnmuted(player)
                    return true
                }
                teardownScope.launch { api.closeSession(session.sessionId) }
            }
            if (kept != null) continue
            val reopened = try {
                when (type) {
                    "livetv" -> reopenLive(player, api, id)
                    "iptv" -> reopenIptv(player, api, id)
                    else -> reopenVod(player, api, type, id, resumeSec)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                continue
            }
            if (!reopened) {
                last = IllegalStateException("The server has nothing to reopen for this $type any more")
                continue
            }
            if (mediaId != id) return true
            if (keepPaused) endPreload(player) else playUnmuted(player)
            return true
        }
    } finally {
        isRecovering = false
        notice.cancel()
        // Only what the notice itself raised: a reconnect that reopened the stream has
        // already been through load(), which owns these.
        if (recoveryNotice) {
            recoveryNotice = false
            isPreparing = false
            preparingNote = null
            onPreparingCancel = null
        }
    }
    failWith(last ?: IllegalStateException("Could not reopen the stream"), diagnosticContext())
    return true
}

private suspend fun GlobalPlayerState.reopenVod(
    player: Player,
    api: KobblerApi,
    type: String,
    id: String,
    resumeSec: Int,
): Boolean {
    // Rebased, not seeked: a reconnect forty minutes into a transcode that started over
    // from zero has nothing to seek into.
    val rebaseTo = resumeRequestSeconds(resumeSec)
    val session = when (type) {
        "movie" -> api.getMovieStreamSession(id, rebaseTo)
        else -> api.getEpisodeStreamSession(id, rebaseTo)
    } ?: return false
    if (mediaId != id) {
        teardownScope.launch { api.closeSession(session.sessionId) }
        return true
    }
    loadInPlace(player, api, type, id, session, resumeSec)
    return true
}

/** Open [session] for the title already showing, keeping everything the viewer sees about it. */
internal suspend fun GlobalPlayerState.loadInPlace(
    player: Player,
    api: KobblerApi,
    type: String,
    id: String,
    session: KobblerApi.StreamSession,
    resumeSec: Int,
) = load(
    player = player, api = api, type = type, id = id,
    streamUrl = session.url, newSessionId = session.sessionId,
    newTitle = title, newSubtitle = subtitle,
    newPosterUrl = posterUrl, newLogoUrl = logoUrl,
    destination = detailDestination,
    seekToSec = resumeSeekSeconds(resumeSec, session.serverStartSeconds),
    session = session,
)

/**
 * Tune the channel again.
 *
 * A live session is a tuner lease, not a playlist: there is no position to restore and
 * nothing to resume — the broadcast is wherever it is now. The old lease was handed back
 * before this ran, so the retune is competing with nothing.
 */
private suspend fun GlobalPlayerState.reopenLive(
    player: Player,
    api: KobblerApi,
    id: String,
): Boolean {
    val session = api.openLiveSession(id)
    if (mediaId != id) {
        teardownScope.launch { api.closeLiveSession(session.sessionId) }
        return true
    }
    applyLiveSession(session, player, api, id, title, posterUrl, subtitle)
    return true
}

/**
 * Probe the channel's sources again.
 *
 * An IPTV session is a chosen upstream, not a file: the server walks the channel's
 * sources and picks one that answers, so reopening is that same walk — which may well
 * land on a different source than the one that just died, and that is the point.
 */
private suspend fun GlobalPlayerState.reopenIptv(
    player: Player,
    api: KobblerApi,
    id: String,
): Boolean {
    val session = api.playIptvChannel(id)
    val relUrl = session.playlistUrl ?: session.directUrl ?: return false
    if (mediaId != id) {
        teardownScope.launch { api.closeSession(session.sessionId) }
        return true
    }
    val url = if (relUrl.startsWith("http://") || relUrl.startsWith("https://")) relUrl
    else "${api.apiUrl}$relUrl"
    load(
        player = player, api = api, type = "iptv", id = id,
        streamUrl = url, newSessionId = session.sessionId,
        newTitle = title, newSubtitle = subtitle,
        newPosterUrl = posterUrl, newLogoUrl = null,
        destination = null,
    )
    isLive = true
    return true
}

/**
 * What the error dialog's retry button does.
 *
 * A deliberate press is not part of any storm, so it gets a fresh budget — the
 * alternative is a button that does nothing once the automatic attempts are spent.
 */
suspend fun GlobalPlayerState.retryPlayback(player: Player, api: KobblerApi) {
    recoveryMarks.clear()
    isRecovering = false
    loadingError = null
    recoverPlayback(player, api)
}

private fun GlobalPlayerState.spendRecoveryBudget(): Boolean {
    recoveryMarks.retainAll { it.elapsedNow() < RECOVERY_WINDOW }
    if (recoveryMarks.size >= MAX_RECOVERIES_IN_WINDOW) return false
    recoveryMarks += TimeSource.Monotonic.markNow()
    return true
}
