package dev.kobbler.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.data.CastSkip
import dev.kobbler.app.data.CastTracks
import dev.kobbler.app.data.ClientCapabilities
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The namespace the Kobbler receiver app listens on. Shared with the web sender. */
const val CAST_NAMESPACE = "urn:x-cast:com.kobbler.cast"

/** The device a cast session is running on, or is still being opened on. */
data class CastDevice(val name: String, val connecting: Boolean = false)

/**
 * What to put on the receiver.
 *
 * [payloadJson] is the session the receiver needs in order to talk to the server itself:
 * the media alone is not enough, because it saves the position, follows skip segments and
 * fetches the next episode on its own. It rides on the load as `customData.kobblerSession`
 * and goes over the custom namespace as well — the receiver accepts it either way, and
 * which one arrives first is not ours to decide.
 */
data class CastLoad(
    val contentUrl: String,
    val contentType: String,
    val payloadJson: String,
    val startPositionSec: Int,
    val title: String,
    val subtitle: String?,
    /** Fetched by the phone's own media controls, which hold no login: it carries a token. */
    val imageUrl: String?,
)

/** What the receiver has to say for itself. */
sealed interface CastEvent {
    /** Where it is. Broadcast every few seconds while playing, and on request. */
    data class Position(
        val positionSec: Double,
        val durationSec: Double,
        val isPlaying: Boolean,
    ) : CastEvent

    /** What the receiver is playing, asked for when this device joins a cast already running. */
    data class NowPlaying(
        val mediaId: String,
        val mediaType: String,
        val title: String,
        val subtitle: String?,
        val posterUrl: String?,
        val positionSec: Double,
        val durationSec: Double,
        val isPlaying: Boolean,
    ) : CastEvent

    /** Nothing left to play — the title ended and no next episode followed it. */
    data object Finished : CastEvent

    /** A read-aloud's stream ran out. The session stays: what is read next is the reader's call. */
    data object TtsFinished : CastEvent

    /** The credential the receiver was handed has expired. */
    data object Expired : CastEvent

    /** The receiver finished an episode and went on to the next one by itself. */
    data class EpisodeChanged(val episodeId: String, val showTitle: String, val label: String) : CastEvent

    /** What the receiver probed itself for, asked for as this device attaches. */
    data class Capabilities(val capabilities: ClientCapabilities) : CastEvent

    data class TracksChanged(val tracks: CastTracks) : CastEvent

    /** A cast book reached the end of one file and went on to the next. */
    data class AudiobookFileChanged(val fileIndex: Int) : CastEvent

    /** Null once the receiver has left the segment. */
    data class SkipChanged(val skip: CastSkip?) : CastEvent
}

private val eventJson = Json { ignoreUnknownKeys = true }

/**
 * Read a message off the namespace.
 *
 * The receiver broadcasts to every sender attached to it, and most of what it says is
 * for the web client's benefit. Anything this client cannot act on reads as null rather
 * than as an error.
 */
fun parseCastEvent(json: String): CastEvent? = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val payload = root["payload"] as? JsonObject
    fun num(key: String) = payload?.get(key)?.jsonPrimitive?.doubleOrNull ?: 0.0
    fun str(key: String) = payload?.get(key)?.jsonPrimitive?.contentOrNull
    val type = root["type"]?.jsonPrimitive?.content
    val mediaId = str("mediaId")
    val mediaType = str("mediaType")
    if (type == "STATUS_RESPONSE" && mediaId != null && mediaType != null) {
        val metadata = payload?.get("metadata")?.jsonObject
        fun meta(key: String) = metadata?.get(key)?.jsonPrimitive?.contentOrNull
        return@runCatching CastEvent.NowPlaying(
            mediaId = mediaId,
            mediaType = mediaType,
            title = meta("title").orEmpty(),
            subtitle = meta("subtitle"),
            posterUrl = meta("posterUrl"),
            positionSec = num("currentTime"),
            durationSec = num("duration"),
            isPlaying = payload?.get("playing")?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }
    when (type) {
        // Same shape, different prompt: one is offered, the other was asked for.
        "POSITION_UPDATE", "STATUS_RESPONSE" -> CastEvent.Position(
            positionSec = num("currentTime"),
            durationSec = num("duration"),
            isPlaying = payload?.get("playing")?.jsonPrimitive?.booleanOrNull ?: false,
        )
        "CAPABILITIES" -> CastEvent.Capabilities(eventJson.decodeFromJsonElement(payload ?: return@runCatching null))
        "TRACKS" -> CastEvent.TracksChanged(eventJson.decodeFromJsonElement(payload ?: return@runCatching null))
        "SKIP_SEGMENT" -> CastEvent.SkipChanged(payload?.let { eventJson.decodeFromJsonElement<CastSkip>(it) })
        "AUDIOBOOK_FILE_CHANGED" -> CastEvent.AudiobookFileChanged(num("fileIndex").toInt())
        "MEDIA_FINISHED" -> CastEvent.Finished
        "TTS_FINISHED" -> CastEvent.TtsFinished
        "SESSION_EXPIRED" -> CastEvent.Expired
        "EPISODE_CHANGED" -> CastEvent.EpisodeChanged(
            episodeId = str("episodeId") ?: return@runCatching null,
            showTitle = str("showTitle").orEmpty(),
            label = listOfNotNull(
                "S${str("seasonIdentifier")} E${str("episodeIdentifier")}",
                str("title")?.takeIf { it.isNotBlank() },
            ).joinToString(" • "),
        )
        else -> null
    }
}.getOrNull()

/**
 * A cast session, from the app's side of it.
 *
 * Split from [castManager] so the handover can be driven without a device in the room:
 * what goes on the wire is [CastLoad] and [parseCastEvent], and what the app does with a
 * session is the same everywhere. Only the plumbing underneath is per-platform.
 */
interface CastSender {
    /** The device in use, or null when nothing is connected. */
    val device: StateFlow<CastDevice?>

    /** Everything the receiver says, parsed. */
    val events: SharedFlow<CastEvent>

    /**
     * Begin listening for cast sessions.
     *
     * Idempotent, and a no-op until the server has told this client which receiver app
     * to talk to — the id arrives with the config, which is after the first screen.
     */
    fun start()

    /** Hand the receiver a title to play. */
    suspend fun load(load: CastLoad)

    /** Send a message on [CAST_NAMESPACE]. */
    suspend fun send(type: String, payloadJson: String)

    /**
     * Move the receiver's playhead.
     *
     * Not a `SKIP_TO` on the namespace, which the receiver has no handler for: it moves
     * for a SEEK, which it intercepts to restart the transcode at the right segment.
     */
    suspend fun seekTo(positionSeconds: Int)

    suspend fun togglePlayPause()

    /** End the session, which hands playback back to this device. */
    suspend fun stop()
}

expect fun castManager(): CastSender

/**
 * Save the Cast receiver app ID from server config.
 * On Android, this persists to SharedPreferences so CastOptionsProvider can read it.
 * No-op on other platforms.
 */
expect fun saveCastAppId(appId: String?)

/** Draws nothing where this device cannot send to a receiver. */
@Composable
expect fun CastButton(modifier: Modifier = Modifier, tint: Color = Color.Unspecified)
