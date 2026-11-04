package dev.kobbler.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.kobbler.app.data.CastInitSession
import dev.kobbler.app.data.CastMetadata
import dev.kobbler.app.data.ClientCapabilities
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.castCapabilities
import dev.kobbler.app.data.castContentType
import dev.kobbler.app.data.castMethod
import dev.kobbler.app.data.withPlaybackToken
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.platform.CastLoad
import dev.kobbler.app.platform.CastSender
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.serialization.json.Json

internal val CastableTypes = setOf("movie", "episode")

/** Encoded for a receiver that reads what it knows and ignores the rest. */
private val castJson = Json { encodeDefaults = true; explicitNulls = true }

/**
 * What to put on the receiver to continue what this device is playing.
 *
 * Null when there is nothing to hand over: a title with no session behind it is either
 * live — which has no receiver-side story — or already gone.
 */
internal suspend fun GlobalPlayerState.castLoad(
    api: KobblerApi,
    capabilities: ClientCapabilities,
    positionSec: Int,
    durationSec: Double,
): CastLoad? {
    val ownStreamUrl = lastStreamUrl ?: return null
    val ownSessionId = sessionId ?: return null
    val type = mediaType?.takeIf { it in CastableTypes } ?: return null
    // The receiver calls the API as a client of its own — for the position it saves, the
    // segments it follows and the next episode it fetches — and it holds no login.
    val token = api.castToken() ?: return null
    // This device plays nearly any file as it is, so its own session is rarely one the
    // receiver can open. A debrid stream has no library title to ask for again.
    val own = mediaId?.takeUnless { isDebrid }?.let { id ->
        runCatching { api.createCastSession(type, id, capabilities, positionSec) }.getOrNull()
    }
    val streamUrl = own?.url ?: ownStreamUrl
    val method = castMethod(own?.method ?: sessionMethod)
    val direct = method == "direct_play"
    val startOffset = own?.serverStartSeconds ?: 0
    val payload = CastInitSession(
        baseUrl = api.apiUrl,
        token = token,
        sessionId = own?.sessionId ?: ownSessionId,
        mediaId = mediaId,
        mediaType = type,
        playlistUrl = streamUrl.takeUnless { direct },
        directUrl = streamUrl.takeIf { direct },
        method = method,
        duration = own?.durationSeconds?.takeIf { it > 0 } ?: durationSec,
        subtitles = own?.subtitles ?: serverSubtitleTracks,
        audioTracks = own?.audioTracks ?: serverAudioTracks,
        thumbnailsUrl = own?.thumbnailsUrl ?: thumbnailsUrl,
        chapters = own?.chapters ?: chapters,
        skipSegments = own?.skipSegments ?: skipSegments,
        capabilities = capabilities,
        metadata = CastMetadata(title = title, subtitle = subtitle, posterUrl = posterUrl),
        startOffset = startOffset.toDouble(),
    )
    return CastLoad(
        contentUrl = streamUrl,
        contentType = castContentType(method),
        payloadJson = castJson.encodeToString(payload),
        startPositionSec = (positionSec - startOffset).coerceAtLeast(0),
        title = title,
        subtitle = subtitle,
        imageUrl = posterUrl?.let { if (it.startsWith(api.apiUrl)) withPlaybackToken(it, token) else it },
    )
}

/**
 * Move playback between this device and a cast receiver.
 *
 * A cast session is not a second player, it is the same watch continued somewhere else:
 * the local stream is paused where it stood, the receiver is handed that position, and
 * the transport on screen becomes the mini controller. Handing back reverses it, at
 * wherever the receiver had got to — so ending a cast never costs the viewer their place.
 *
 * Progress is the receiver's to report while it plays, which is why nothing here writes
 * it on a tick: it saves directly against the server every few seconds, and a second
 * writer with a staler position would undo those.
 */
@Composable
fun CastHandoff(
    cast: CastSender,
    gps: GlobalPlayerState,
    api: KobblerApi,
    player: Player?,
    onResumeHere: (mediaType: String, mediaId: String) -> Unit = { _, _ -> },
) {
    LaunchedEffect(cast, api) {
        // The receiver app id arrives with the server config, and there is nothing for
        // the sender to attach to without one. The probe behind it is memoised, so this
        // is the same one the library screens do rather than a request of its own.
        runCatching { api.checkStreamingAvailability() }
        cast.start()
    }

    val device by cast.device.collectAsState()
    // What the receiver is playing, which is not always what this device last loaded:
    // picking the next episode here has to move the cast too, not start a second watch.
    var castedMediaId by remember { mutableStateOf<String?>(null) }
    var receiverDecodes by remember { mutableStateOf<ClientCapabilities?>(null) }

    LaunchedEffect(device?.name, device?.connecting) {
        receiverDecodes = null
        if (device?.connecting == false) cast.send("CAPABILITIES_REQUEST", "")
    }

    LaunchedEffect(device, gps.mediaId) {
        val target = device
        if (target == null) {
            castedMediaId = null
            handBackFromCast(gps, api, player, onResumeHere)
            return@LaunchedEffect
        }
        if (player == null) return@LaunchedEffect
        val mediaId = gps.mediaId
        if (mediaId == null) {
            // Joined a cast that was already running — the app reopened mid-watch.
            if (!target.connecting && gps.castSession == null) cast.send("STATUS_REQUEST", "")
            return@LaunchedEffect
        }
        if (mediaId == castedMediaId) return@LaunchedEffect
        if (gps.mediaType !in CastableTypes) return@LaunchedEffect
        // A title picked while casting never starts here, so the player's clock is still
        // the last title's; the session it was opened with is the only account of it.
        val switching = castedMediaId != null
        val positionMs = gps.castSession?.takeIf { it.connecting }?.positionMs
            ?: gps.timeline.toAbsoluteMs(if (switching) gps.pendingSeekMs else player.currentMs.value)
        val durationMs = if (switching) ((gps.streamSession?.durationSeconds ?: 0.0) * 1000).toLong()
        else gps.timeline.absoluteDurationMs(player.durationMs.value)
        // Stopped here the moment a receiver is picked: opening the session takes seconds,
        // and the viewer has already chosen where they are watching.
        player.pause()
        if (gps.isFullscreen) {
            gps.isFullscreen = false
            gps.isMiniPlayer = true
            gps.isCastExpanded = true
        }
        if (target.connecting) {
            gps.castSession = CastSessionDto(
                deviceName = target.name,
                mediaId = mediaId,
                mediaType = gps.mediaType,
                mediaTitle = gps.title,
                subtitle = gps.subtitle,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = false,
                connecting = true,
            )
            gps.onCastDisconnect = { gps.teardownScope.launch { cast.stop() } }
            return@LaunchedEffect
        }
        // A receiver from before it could say answers nothing, and is judged by its name.
        val decodes = withTimeoutOrNull(CapabilitiesWait) { snapshotFlow { receiverDecodes }.filterNotNull().first() }
            ?: castCapabilities(target.name)
        val load = gps.castLoad(
            api = api,
            capabilities = decodes,
            positionSec = (positionMs / 1000).toInt(),
            durationSec = durationMs / 1000.0,
        ) ?: return@LaunchedEffect

        cast.load(load)
        gps.castSession = CastSessionDto(
            deviceName = target.name,
            mediaId = mediaId,
            mediaType = gps.mediaType,
            mediaTitle = gps.title,
            subtitle = gps.subtitle,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = true,
        )
        armCastTransport(cast, gps)
        castedMediaId = mediaId
        cast.send("TRACKS_REQUEST", "")
    }

    LaunchedEffect(cast) {
        cast.events.collect { event ->
            when (event) {
                is CastEvent.NowPlaying -> if (gps.castSession == null && gps.mediaId == null) {
                    val name = cast.device.value?.name ?: return@collect
                    gps.castSession = CastSessionDto(
                        deviceName = name,
                        mediaId = event.mediaId,
                        mediaType = event.mediaType,
                        mediaTitle = event.title,
                        subtitle = event.subtitle,
                        positionMs = (event.positionSec * 1000).toLong(),
                        durationMs = (event.durationSec * 1000).toLong(),
                        isPlaying = event.isPlaying,
                    )
                    gps.posterUrl = event.posterUrl
                    armCastTransport(cast, gps)
                    castedMediaId = event.mediaId
                    cast.send("TRACKS_REQUEST", "")
                }
                is CastEvent.Position -> if (!castScrubIsSettling(gps)) {
                    gps.castSession = gps.castSession?.copy(
                        positionMs = (event.positionSec * 1000).toLong(),
                        durationMs = (event.durationSec * 1000).toLong()
                            .takeIf { it > 0 } ?: gps.castSession?.durationMs ?: 0L,
                        isPlaying = event.isPlaying,
                    )
                }
                // The receiver reached the end of what it had and found nothing to
                // follow it. Nothing to hand back to, so the session just closes.
                CastEvent.Finished -> {
                    if (player != null && gps.isActive) gps.stop(player, api, gps.teardownScope)
                    else dropCast(gps)
                    cast.stop()
                }
                // A receiver that has been playing for a day outlives the credential it
                // was handed, and it asks rather than stopping.
                CastEvent.Expired -> api.castToken()?.let { token ->
                    cast.send("TOKEN_REFRESH", """{"token":"$token"}""")
                }
                is CastEvent.EpisodeChanged -> gps.castSession = gps.castSession?.copy(
                    mediaId = event.episodeId,
                    mediaType = "episode",
                    mediaTitle = event.showTitle.ifBlank { gps.castSession?.mediaTitle.orEmpty() },
                    subtitle = event.label,
                    positionMs = 0,
                    tracks = null,
                    skip = null,
                )
                is CastEvent.Capabilities -> receiverDecodes = event.capabilities
                is CastEvent.TracksChanged -> gps.castSession = gps.castSession?.copy(tracks = event.tracks)
                is CastEvent.SkipChanged -> gps.castSession = gps.castSession?.copy(skip = event.skip)
                // A cast book and a read-aloud are their own screens' to follow.
                is CastEvent.AudiobookFileChanged, CastEvent.TtsFinished -> Unit
            }
        }
    }
}

/**
 * How long a scrub owns the transport before the receiver's own account of it is believed.
 *
 * The receiver broadcasts every five seconds, so one that was already in flight when the
 * viewer let go carries the position they scrubbed away from. Believing it drags the bar
 * back for the rest of that interval, which reads as the scrub having been ignored — the
 * seek itself landed.
 */
private val CastScrubSettle = 4.seconds

private val CapabilitiesWait = 2.seconds

/**
 * Move the receiver, and show it as moved.
 *
 * The playhead is put where the viewer left it rather than where the receiver last said
 * it was: the seek is a round trip across the house, and the bar has to answer to the
 * hand on it now.
 */
private fun castSeek(cast: CastSender, gps: GlobalPlayerState, absoluteMs: Long) {
    val at = absoluteMs.coerceAtLeast(0L)
    gps.castSession = gps.castSession?.copy(positionMs = at)
    gps.castScrubbedAt = TimeSource.Monotonic.markNow()
    gps.teardownScope.launch { cast.seekTo((at / 1000).toInt()) }
}

private fun armCastTransport(cast: CastSender, gps: GlobalPlayerState) {
    gps.onCastPlayPause = {
        gps.castSession = gps.castSession?.let { it.copy(isPlaying = !it.isPlaying) }
        gps.teardownScope.launch { cast.togglePlayPause() }
    }
    gps.onCastDisconnect = { gps.teardownScope.launch { cast.stop() } }
    gps.onCastSeek = { absoluteMs -> castSeek(cast, gps, absoluteMs) }
    gps.onCastSubtitle = { id ->
        gps.castSession = gps.castSession?.let { it.copy(tracks = it.tracks?.copy(activeSubtitleId = id)) }
        gps.teardownScope.launch { cast.send("SUBTITLE_CHANGE", "{\"trackId\":${id ?: "null"}}") }
    }
    gps.onCastAudio = { streamIndex ->
        gps.castSession = gps.castSession?.let { it.copy(tracks = it.tracks?.copy(activeAudioIndex = streamIndex)) }
        gps.teardownScope.launch { cast.send("AUDIO_SWITCH", "{\"streamIndex\":$streamIndex}") }
    }
}

private fun dropCast(gps: GlobalPlayerState) {
    gps.castSession = null
    gps.isCastExpanded = false
    gps.onCastPlayPause = null
    gps.onCastDisconnect = null
    gps.onCastSeek = null
    gps.onCastSubtitle = null
    gps.onCastAudio = null
    gps.castScrubbedAt = null
}

private fun castScrubIsSettling(gps: GlobalPlayerState): Boolean =
    gps.castScrubbedAt?.elapsedNow()?.let { it < CastScrubSettle } == true

/**
 * Put the watch back on this device, at the receiver's position.
 *
 * The local player was only paused, so its stream is still open — but it is paused at
 * where the handover happened, which by now is however long the cast ran ago.
 */
private suspend fun handBackFromCast(
    gps: GlobalPlayerState,
    api: KobblerApi,
    player: Player?,
    onResumeHere: (mediaType: String, mediaId: String) -> Unit,
) {
    val cast = gps.castSession ?: return
    dropCast(gps)
    // What the receiver was playing is not open here: it went on to another episode, or
    // this device joined a cast already running. It is opened the way any title is.
    if (cast.mediaId != gps.mediaId) {
        if (player != null && gps.isActive) gps.stop(player, api, gps.teardownScope)
        val type = cast.mediaType ?: return
        val id = cast.mediaId ?: return
        if (cast.positionMs > 0) gps.reportProgress(api, type, id, cast.positionMs)
        onResumeHere(type, id)
        return
    }
    if (player != null) {
        if (cast.positionMs > 0) player.landAt(gps.timeline.toPlayerMs(cast.positionMs), resume = gps.isVideoPlaying)
        else if (gps.isVideoPlaying) player.play()
    }
    val mediaType = gps.mediaType
    val mediaId = gps.mediaId
    if (mediaType != null && mediaId != null && cast.positionMs > 0) {
        gps.reportProgress(api, mediaType, mediaId, cast.positionMs)
    }
}
