package dev.kobbler.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.kobbler.app.data.CastInitSession
import dev.kobbler.app.data.CastMetadata
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.castCapabilities
import dev.kobbler.app.data.castContentType
import dev.kobbler.app.data.castMethod
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.platform.CastLoad
import dev.kobbler.app.platform.CastSender
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.serialization.json.Json

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
    deviceName: String,
    positionSec: Int,
    durationSec: Double,
): CastLoad? {
    val streamUrl = lastStreamUrl ?: return null
    val session = sessionId ?: return null
    val type = mediaType?.takeIf { it == "movie" || it == "episode" } ?: return null
    val direct = castMethod(sessionMethod) == "direct_play"
    // The receiver calls the API as a client of its own — for the position it saves, the
    // segments it follows and the next episode it fetches — and it holds no login.
    val token = api.castToken() ?: return null
    val payload = CastInitSession(
        baseUrl = api.apiUrl,
        token = token,
        sessionId = session,
        mediaId = mediaId,
        mediaType = type,
        playlistUrl = streamUrl.takeUnless { direct },
        directUrl = streamUrl.takeIf { direct },
        method = castMethod(sessionMethod),
        duration = durationSec,
        subtitles = serverSubtitleTracks,
        audioTracks = serverAudioTracks,
        thumbnailsUrl = thumbnailsUrl,
        chapters = chapters,
        skipSegments = skipSegments,
        capabilities = castCapabilities(deviceName),
        metadata = CastMetadata(title = title, subtitle = subtitle, posterUrl = posterUrl),
    )
    return CastLoad(
        contentUrl = streamUrl,
        contentType = castContentType(sessionMethod),
        payloadJson = castJson.encodeToString(payload),
        startPositionSec = positionSec,
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
fun CastHandoff(cast: CastSender, gps: GlobalPlayerState, api: KobblerApi, player: Player?) {
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

    LaunchedEffect(device, gps.mediaId) {
        val name = device?.name
        if (name == null) {
            castedMediaId = null
            handBackFromCast(gps, api, player)
            return@LaunchedEffect
        }
        if (player == null) return@LaunchedEffect
        val mediaId = gps.mediaId ?: return@LaunchedEffect
        if (mediaId == castedMediaId) return@LaunchedEffect
        val positionMs = gps.timeline.toAbsoluteMs(player.currentMs.value)
        val durationMs = gps.timeline.absoluteDurationMs(player.durationMs.value)
        val load = gps.castLoad(
            api = api,
            deviceName = name,
            positionSec = (positionMs / 1000).toInt(),
            durationSec = durationMs / 1000.0,
        ) ?: return@LaunchedEffect

        player.pause()
        cast.load(load)
        gps.castSession = CastSessionDto(
            deviceName = name,
            mediaTitle = gps.title,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = true,
        )
        gps.onCastPlayPause = { gps.teardownScope.launch { cast.togglePlayPause() } }
        gps.onCastDisconnect = { gps.teardownScope.launch { cast.stop() } }
        gps.onCastSeek = { absoluteMs -> castSeek(cast, gps, absoluteMs) }
        castedMediaId = mediaId
    }

    LaunchedEffect(cast) {
        cast.events.collect { event ->
            when (event) {
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
                CastEvent.Finished -> cast.stop()
                // A receiver that has been playing for a day outlives the credential it
                // was handed, and it asks rather than stopping.
                CastEvent.Expired -> api.castToken()?.let { token ->
                    cast.send("TOKEN_REFRESH", """{"token":"$token"}""")
                }
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

private fun castScrubIsSettling(gps: GlobalPlayerState): Boolean =
    gps.castScrubbedAt?.elapsedNow()?.let { it < CastScrubSettle } == true

/**
 * Put the watch back on this device, at the receiver's position.
 *
 * The local player was only paused, so its stream is still open — but it is paused at
 * where the handover happened, which by now is however long the cast ran ago.
 */
private suspend fun handBackFromCast(gps: GlobalPlayerState, api: KobblerApi, player: Player?) {
    val cast = gps.castSession ?: return
    gps.castSession = null
    gps.onCastPlayPause = null
    gps.onCastDisconnect = null
    gps.onCastSeek = null
    gps.castScrubbedAt = null
    if (player != null) {
        if (cast.positionMs > 0) player.seekTo(gps.timeline.toPlayerMs(cast.positionMs))
        if (gps.isVideoPlaying) player.play()
    }
    val mediaType = gps.mediaType
    val mediaId = gps.mediaId
    if (mediaType != null && mediaId != null && cast.positionMs > 0) {
        gps.reportProgress(api, mediaType, mediaId, cast.positionMs)
    }
}
