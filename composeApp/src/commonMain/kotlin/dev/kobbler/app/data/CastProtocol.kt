package dev.kobbler.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The session handed to a cast receiver, which is the server's own session plus the two
 * things the receiver cannot work out for itself: where to reach the API, and a
 * credential to reach it with.
 *
 * Field names are the receiver's, and the nested shapes are the server's own — the
 * receiver reads `start_seconds` off a skip segment either way, so the API DTOs go
 * across as they arrived rather than being restated here.
 */
@Serializable
data class CastInitSession(
    val baseUrl: String,
    val token: String,
    val sessionId: String,
    val mediaId: String?,
    val mediaType: String,
    val playlistUrl: String?,
    val directUrl: String?,
    val method: String,
    val duration: Double,
    val subtitles: List<PlaybackSubtitleTrackDto> = emptyList(),
    val audioTracks: List<AudioTrackDto> = emptyList(),
    val thumbnailsUrl: String? = null,
    val chapters: List<SessionChapter> = emptyList(),
    val skipSegments: List<SkipSegment> = emptyList(),
    val capabilities: CastCapabilities,
    val metadata: CastMetadata,
)

@Serializable
data class CastMetadata(
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
)

/** Shaped as the server's `ClientCapabilities`, because that is what it is used as. */
@Serializable
data class CastCapabilities(
    @SerialName("video_codecs") val videoCodecs: List<String>,
    @SerialName("audio_codecs") val audioCodecs: List<String>,
    val containers: List<String> = listOf("mp4"),
)

/**
 * What a cast device can decode, from the only thing a sender knows about it: its name.
 *
 * The receiver passes this on when it mints its own sessions, so getting it wrong costs
 * a transcode the device did not need — or a stream it cannot play. Deliberately the
 * same profile the web sender uses; a device does not gain codecs by being cast to from
 * a phone.
 */
fun castCapabilities(deviceName: String): CastCapabilities {
    val name = deviceName.lowercase()
    val googleTv = listOf("google tv", "ccgtv", "shield", "onn", "bravia").any { it in name }
    val ultra = "ultra" in name
    return CastCapabilities(
        videoCodecs = buildList {
            add("h264")
            add("vp9")
            if (googleTv || ultra) add("hevc")
        },
        audioCodecs = buildList {
            add("aac")
            add("opus")
            add("mp3")
            add("flac")
            if (googleTv) {
                add("ac3")
                add("eac3")
            }
        },
    )
}

/** What the receiver was handed: a playlist to follow, or a file to play. */
fun castMethod(sessionMethod: String?): String =
    if (sessionMethod == "direct_play") "direct_play" else "hls"

fun castContentType(sessionMethod: String?): String =
    if (castMethod(sessionMethod) == "direct_play") "video/mp4" else "application/x-mpegURL"
