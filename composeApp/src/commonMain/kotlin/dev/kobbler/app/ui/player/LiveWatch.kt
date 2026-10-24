package dev.kobbler.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.kobbler.app.data.ApiException
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveRecording
import dev.kobbler.app.data.LiveSession
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.ui.dashboard.livetv.LastLiveChannelKey
import dev.kobbler.app.ui.dashboard.livetv.localHhMm
import dev.kobbler.app.ui.dashboard.livetv.parseLiveInstant
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull

// The sweeper reaps released leases every 30 s; a beat well inside that keeps a lease
// that is genuinely in use from ever being a candidate.
private const val HeartbeatIntervalMs = 15_000L

// Reachability moves slowly: with keep-from-tune-in the buffer start only changes once
// the 3 h cap begins sliding it.
private const val BufferPollIntervalMs = 10_000L
private const val TimeshiftParseTimeoutMs = 5_000L

/**
 * Raise the player on [channel].
 *
 * No probe and no pre-buffer guard: the session is held after ~15 ms and the tuner's own
 * TTFB is ~0.5 s, so there is nothing to narrate — a "this can take a minute" note would
 * describe the IPTV path, not this one.
 */
fun startLiveWatch(
    channel: LiveChannel,
    player: Player?,
    gps: GlobalPlayerState,
    api: KobblerApi,
    scope: CoroutineScope,
) {
    if (player == null || gps.isPreparing) return
    rememberLastLiveChannel(channel)
    PlaybackTrace.begin("tune ${channel.name}")

    gps.liveSessionId?.let { sid -> scope.launch { api.closeLiveSession(sid) } }
    gps.sessionId = null
    gps.liveSessionId = null
    gps.mediaType = "livetv"
    gps.mediaId = channel.id
    gps.title = channel.name
    gps.subtitle = channel.now?.title
    gps.posterUrl = channel.now?.imageUrl ?: channel.now?.posterUrl ?: channel.logoUrl
    gps.logoUrl = null
    gps.detailDestination = null
    gps.chapters = emptyList()
    gps.skipSegments = emptyList()
    gps.thumbnailsUrl = null
    gps.fileId = null
    gps.lufs = null
    gps.serverAudioTracks = emptyList()
    gps.selectedServerAudioIndex = null
    gps.serverSubtitleTracks = emptyList()
    gps.selectedServerSubtitleTrackId = null
    gps.isLive = true
    gps.loadingError = null
    gps.isPreparing = true
    gps.preparingNote = null
    gps.isFullscreen = true

    scope.launch {
        try {
            val session = api.openLiveSession(channel.id, player.decodesFieldPictures)
            if (gps.mediaId != channel.id || !gps.isPreparing) return@launch
            gps.applyLiveSession(session, player, api, channel.id, channel.name, channel.logoUrl, channel.now?.title)
            gps.playUnmuted(player)
        } catch (e: Exception) {
            if (gps.mediaId == channel.id) {
                gps.failWith(
                    e,
                    listOf(
                        "Channel: ${channel.name}",
                        "ID: ${channel.id}",
                        "Endpoint: ${api.apiUrl}/api/v1/admin/livetv/sessions",
                    ),
                )
            }
        } finally {
            if (gps.mediaId == channel.id) {
                gps.isPreparing = false
                gps.preparingNote = null
            }
        }
    }
}

/**
 * Move an existing session to [channel] without opening a new one — the server keeps the
 * old lease warm briefly, so flicking back is instant.
 *
 * The channel being left is not touched until [GlobalPlayerState.load] swaps the source
 * out from under it. The server takes the new channel's tuner before it lets the old one
 * go, so what is on screen for the length of the request is a live picture rather than
 * the tail of a stream that has stopped — and a change that keeps a picture until it has
 * another one needs nothing laid over it to be bearable.
 */
suspend fun switchLiveChannel(
    channel: LiveChannel,
    player: Player,
    gps: GlobalPlayerState,
    api: KobblerApi,
) {
    val sid = gps.liveSessionId
    val previous = gps.mediaId
    if (previous == channel.id) return
    rememberLastLiveChannel(channel)
    // The one path with no numbers on it, and the one whose whole quality is how long it
    // takes: the trace runs from the press to the first frame of the new channel.
    PlaybackTrace.begin("switch to ${channel.name}")
    // Claim the target before the request so a second switch in flight wins over this one.
    gps.mediaId = channel.id
    gps.title = channel.name
    gps.subtitle = channel.now?.title
    gps.posterUrl = channel.logoUrl
    try {
        val session = if (sid != null) api.switchLiveSessionChannel(sid, channel.id, player.decodesFieldPictures)
        else api.openLiveSession(channel.id, player.decodesFieldPictures)
        if (gps.mediaId != channel.id) return
        gps.applyLiveSession(session, player, api, channel.id, channel.name, channel.logoUrl, channel.now?.title)
        gps.playUnmuted(player)
    } catch (e: Exception) {
        if (gps.mediaId == channel.id) {
            gps.mediaId = previous
            gps.failWith(
                e,
                listOf(
                    "Channel: ${channel.name}",
                    "Session: ${sid ?: "(new)"}",
                    "Endpoint: ${api.apiUrl}/api/v1/admin/livetv/sessions",
                ),
            )
        }
    }
}

/**
 * Takes the channel's identity rather than a [LiveChannel]: a reconnect has only what the
 * player state remembers of the channel it was watching, and these four fields are the
 * whole of what a tune needs.
 */
internal suspend fun GlobalPlayerState.applyLiveSession(
    session: LiveSession,
    player: Player,
    api: KobblerApi,
    channelId: String,
    channelName: String,
    channelLogoUrl: String?,
    fallbackNowTitle: String? = null,
) {
    val url = session.streamUrl
        ?: throw IllegalStateException("Server returned no stream URL (session_id=${session.sessionId})")
    liveSessionId = session.sessionId
    liveTunersInUse = session.tunersInUse
    liveTunerCount = session.tunerCount
    liveNowGuideId = session.now?.id
    liveNowStart = session.now?.startTime
    liveNowEnd = session.now?.endTime
    livePlaylistUrl = session.playlistUrl
    // Every tune and every channel flip starts on the raw TS; timeshift is only ever
    // entered deliberately, so a channel change stays at tuner speed.
    liveTimeshift = false
    liveBuffer = null
    liveTuneError = null
    livePausedAtSeconds = null
    isLive = true
    videoWidth = session.width
    videoHeight = session.height
    // Only the raw broadcast is field-coded. A transcode has already been deinterlaced,
    // which includes the buffer this session is filling — see enterLiveTimeshift.
    liveFieldCoded = session.method == "direct" &&
        session.videoCodec == "h264" &&
        (session.interlaced ?: true)
    // Set after load() clears it: the buffer grows as it is watched, so the scrubber's
    // thumbnails are generated from the tuner feed rather than read off a finished file.
    val thumbnails = session.thumbnailsUrl
    load(
        player = player, api = api,
        type = "livetv", id = channelId,
        streamUrl = url, newSessionId = null,
        newTitle = channelName, newSubtitle = session.now?.title ?: fallbackNowTitle,
        newPosterUrl = channelLogoUrl, newLogoUrl = null,
        destination = null,
    )
    // load() takes its dimensions from a generic session payload a live tune has none
    // of, so the channel's own are re-applied after it.
    videoWidth = session.width
    videoHeight = session.height
    thumbnailsUrl = thumbnails
}

/**
 * Play a recording. A matched recording plays as its library episode/movie so resume,
 * Up next and progress all work on it exactly as they do on a downloaded copy; an
 * unmatched one stays watchable under its own id.
 */
fun startRecordingPlayback(
    recording: LiveRecording,
    player: Player?,
    gps: GlobalPlayerState,
    api: KobblerApi,
    scope: CoroutineScope,
) {
    if (player == null || gps.isPreparing) return

    val type = when {
        recording.episodeId != null -> "episode"
        recording.movieId != null -> "movie"
        else -> "recording"
    }
    val id = recording.episodeId ?: recording.movieId ?: recording.id

    gps.liveSessionId?.let { sid -> scope.launch { api.closeLiveSession(sid) } }
    gps.liveSessionId = null
    gps.mediaType = type
    gps.mediaId = id
    gps.title = recording.title
    gps.subtitle = listOfNotNull(recording.episodeTitle, recording.channelName).firstOrNull()
    gps.detailDestination = null
    gps.isLive = false
    gps.loadingError = null
    gps.isPreparing = true
    gps.preparingNote = null
    gps.isFullscreen = true

    scope.launch {
        try {
            val session = api.getLiveRecordingPlayback(recording.id)
                ?: throw IllegalStateException("Server returned no stream URL for recording ${recording.id}")
            if (gps.mediaId != id || !gps.isPreparing) return@launch
            gps.adPosture = recording.adPosture
            gps.adSkipDisabled = recording.adSkipDisabled
            gps.load(
                player = player, api = api,
                type = type, id = id,
                streamUrl = session.url, newSessionId = session.sessionId,
                newTitle = recording.title, newSubtitle = gps.subtitle,
                newPosterUrl = recording.imageUrl, newLogoUrl = null,
                destination = null,
                session = session,
            )
            gps.playUnmuted(player)
        } catch (e: Exception) {
            if (gps.mediaId == id) {
                gps.failWith(
                    e,
                    listOf(
                        "Recording: ${recording.title}",
                        "ID: ${recording.id}",
                        "Endpoint: ${api.apiUrl}/api/v1/admin/livetv/recordings/${recording.id}/playback",
                    ),
                )
            }
        } finally {
            if (gps.mediaId == id) {
                gps.isPreparing = false
                gps.preparingNote = null
            }
        }
    }
}

/** So the guide, and the next session, can open on what was last being watched. */
private fun rememberLastLiveChannel(channel: LiveChannel) {
    UserSettings.putString(LastLiveChannelKey, channel.id)
}

@Composable
fun LiveSessionHeartbeat(
    gps: GlobalPlayerState,
    api: KobblerApi,
    player: Player? = null,
    intervalMs: Long = HeartbeatIntervalMs,
) {
    val sid = gps.liveSessionId
    LaunchedEffect(sid, player) {
        if (sid == null) return@LaunchedEffect
        while (true) {
            delay(intervalMs)
            // A lease the server has already let go is a tuner somebody else can now
            // take, and beating on it forever is how a channel goes quiet with the
            // picture still up. Only a 404 counts: everything else is the network.
            val gone = try {
                api.liveSessionHeartbeat(sid)
                false
            } catch (e: dev.kobbler.app.data.ApiException) {
                e.statusCode == 404
            } catch (_: Exception) {
                false
            }
            if (gone && player != null && gps.liveSessionId == sid && !gps.isRecovering) {
                // Not in this effect: the retune replaces the lease id it is keyed on.
                gps.teardownScope.launch { gps.recoverPlayback(player, api) }
                return@LaunchedEffect
            }
        }
    }
}

/**
 * Move this session's playback from the raw TS to its own buffered playlist and seek.
 *
 * No new session and no new lease — the server tees one tuner connection into both, so
 * this is a source swap on the same player and the heartbeat, which is keyed to the
 * unchanged session id, never restarts.
 *
 * [seekToSec] is an offset into the buffer; null means the live edge.
 */
suspend fun enterLiveTimeshift(
    gps: GlobalPlayerState,
    player: Player,
    api: KobblerApi,
    seekToSec: Int?,
): Boolean {
    val url = gps.livePlaylistUrl ?: return false
    val channelId = gps.mediaId ?: return false

    if (!timeshiftNeedsSourceSwap(gps.liveTimeshift, gps.lastStreamUrl, url)) {
        // Nothing to load — the player is already on the buffer, either because an earlier
        // rewind put it there or because the tune itself was a transcode, whose stream URL
        // is this playlist. Reloading it swapped a source for itself and cost a full
        // surface teardown, which is the jump a step back used to show.
        gps.liveTimeshift = true
        player.seekTo(seekToSec?.times(1000L) ?: liveEdgeSeekMs(player.durationMs.value))
        player.play()
        return true
    }

    gps.liveTimeshift = true
    // The buffer is the server's own transcode, deinterlaced on the way in and capped
    // well below the broadcast's height. Whatever the tuner's stream needed, this is a
    // different stream and needs none of it.
    gps.liveFieldCoded = false
    // The swap detaches the surface and re-parses. Without a label that reads as the
    // stream dropping, which is what a rewind off the raw TS looked like.
    gps.liveSeekingLabel = rewindLabel(gps, seekToSec)
    // The stills are generated off the feed the buffer is filled from, and load() clears
    // them along with everything else it reads out of a session payload a live tune has
    // none of. The buffer's own geometry is deliberately *not* carried over: it is the
    // server's transcode, and the broadcast's dimensions — anamorphic as often as not —
    // are what the aspect-ratio memory would otherwise measure it with.
    val thumbnails = gps.thumbnailsUrl
    return try {
        gps.load(
            player = player, api = api,
            type = "livetv", id = channelId,
            streamUrl = url, newSessionId = null,
            newTitle = gps.title, newSubtitle = gps.subtitle,
            newPosterUrl = gps.posterUrl, newLogoUrl = null,
            destination = null,
            // Hand the target to load() rather than seeking after it: load opens the
            // playlist at the offset, so the demuxer starts there. A seek issued to a
            // paused, freshly-parsed HLS source does not reliably stick, and when it
            // slips the first frames come from the head of the buffer — hours old.
            seekToSec = seekToSec ?: 0,
        )
        if (gps.mediaId != channelId) return false
        gps.thumbnailsUrl = thumbnails
        if (seekToSec == null) {
            // The live edge is only knowable once the playlist is parsed, so it cannot be
            // expressed as an offset up front. load()'s own wait is unbounded; cap this one.
            withTimeoutOrNull(TimeshiftParseTimeoutMs) { player.durationMs.first { it > 0 } }
            player.seekTo(liveEdgeSeekMs(player.durationMs.value))
        }
        gps.playUnmuted(player)
        true
    } catch (e: Exception) {
        gps.liveTimeshift = false
        if (gps.mediaId == channelId) {
            gps.failWith(
                e,
                listOf(
                    "Timeshift for: ${gps.title}",
                    "Session: ${gps.liveSessionId ?: "(none)"}",
                    "Playlist: $url",
                ),
            )
        }
        false
    } finally {
        gps.liveSeekingLabel = null
    }
}

/** Wall-clock for a buffer offset, so the banner names the moment being rewound to. */
private fun rewindLabel(gps: GlobalPlayerState, seekToSec: Int?): String {
    val target = seekToSec ?: return "Catching up to live"
    val bufferStart = parseLiveInstant(gps.liveBuffer?.startTime)
        ?: return "Rewinding"
    return "Rewinding to ${(bufferStart + target.seconds).localHhMm()}"
}

/**
 * Pause without losing what airs next.
 *
 * The raw TS has no timeline to resume against, so the live edge is read from the buffer
 * at the moment of the pause and kept; the source is not swapped until playback resumes,
 * which is what makes the pause itself instant.
 */
fun pauseLive(gps: GlobalPlayerState, player: Player, api: KobblerApi, scope: CoroutineScope) {
    player.pause()
    if (gps.liveTimeshift) return
    val sid = gps.liveSessionId ?: return
    scope.launch {
        val buffer = runCatching { api.getLiveSessionBuffer(sid) }.getOrNull() ?: return@launch
        gps.liveBuffer = buffer
        gps.livePausedAtSeconds = buffer.liveEdgeSeconds.toInt()
    }
}

/** Resume from where [pauseLive] stopped, on the playlist if the position is known. */
fun resumeLive(gps: GlobalPlayerState, player: Player, api: KobblerApi, scope: CoroutineScope) {
    val at = gps.livePausedAtSeconds
    if (gps.liveTimeshift || at == null) {
        player.play()
        return
    }
    scope.launch {
        // Falls back to simply resuming the raw stream at the live edge when the buffer
        // is not there — a degraded resume beats a dead button.
        if (!enterLiveTimeshift(gps, player, api, seekToSec = at)) player.play()
        gps.livePausedAtSeconds = null
    }
}

/**
 * Keeps the session's now/next current for as long as the channel is up.
 *
 * The tune reads them once and nothing refreshed them, so an hour in the player still
 * named the programme that was on when it opened, [liveProgrammeRegion] marked a slot on
 * the seek bar that had already passed, and Record sent that programme's guide id — which
 * schedules a recording to end before it starts.
 */
@Composable
fun LiveNowPoll(gps: GlobalPlayerState, api: KobblerApi) {
    val channelId = gps.mediaId?.takeIf { gps.mediaType == "livetv" }
    LaunchedEffect(channelId) {
        if (channelId == null) return@LaunchedEffect
        while (true) {
            delay(nextNowRefreshDelay(gps.liveNowEnd, Clock.System.now()))
            val current = runCatching { api.getLiveGuideNow(listOf(channelId)).firstOrNull() }
                .getOrNull()
            // The channel moved on under us; the new tune owns its own poll.
            if (gps.mediaId != channelId) return@LaunchedEffect
            val airing = current?.now ?: continue
            gps.liveNowGuideId = airing.id
            gps.liveNowStart = airing.startTime
            gps.liveNowEnd = airing.endTime
            gps.subtitle = airing.title
        }
    }
}

@Composable
fun LiveBufferPoll(gps: GlobalPlayerState, api: KobblerApi, player: Player?) {
    val sid = gps.liveSessionId
    LaunchedEffect(sid) {
        if (sid == null) {
            gps.liveBuffer = null
            gps.liveTuneError = null
            return@LaunchedEffect
        }
        val stall = BufferStallWatch(BufferStallMs)
        while (true) {
            val polled = runCatching { api.getLiveSessionBuffer(sid) }
            (polled.exceptionOrNull() as? ApiException)?.serverMessage?.let { gps.liveTuneError = it }
            polled.getOrNull()?.let { buffer ->
                gps.liveBuffer = buffer
                gps.liveTuneError = buffer.tuneError
                // A buffer that has stopped growing is one whose recorder died: playing on
                // from it leaves the viewer on frozen, grey frames that never catch up.
                val dead = stall.observe(buffer.liveEdgeSeconds, monotonicNowMs())
                if (dead && gps.liveTimeshift && player != null && !gps.isRecovering) {
                    gps.teardownScope.launch { gps.recoverPlayback(player, api) }
                }
            }
            delay(BufferPollIntervalMs)
        }
    }
}

internal class BufferStallWatch(private val stallMs: Long) {
    private var edge = Double.NaN
    private var grewAt = 0L

    fun observe(liveEdgeSeconds: Double, nowMs: Long): Boolean {
        if (edge.isNaN() || liveEdgeSeconds > edge) {
            edge = liveEdgeSeconds
            grewAt = nowMs
        }
        return nowMs - grewAt >= stallMs
    }
}

private val bufferClock = kotlin.time.TimeSource.Monotonic.markNow()
private fun monotonicNowMs() = bufferClock.elapsedNow().inWholeMilliseconds
private const val BufferStallMs = 20_000L
