package dev.kobbler.app.data

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
    val capabilities: ClientCapabilities,
    val metadata: CastMetadata,
    /** Where in the title the stream begins: a transcode made from a resume starts there. */
    val startOffset: Double = 0.0,
)

@Serializable
data class CastMetadata(
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
)

/** The tracks the receiver can switch between, as it numbers them. */
@Serializable
data class CastTracks(
    val subtitles: List<CastTextTrack> = emptyList(),
    val activeSubtitleId: Int? = null,
    val audioTracks: List<CastAudioTrack> = emptyList(),
    val activeAudioIndex: Int? = null,
)

@Serializable
data class CastTextTrack(
    val id: Int,
    val language: String? = null,
    val title: String? = null,
    val forced: Boolean = false,
    val hearingImpaired: Boolean = false,
)

@Serializable
data class CastAudioTrack(
    val streamIndex: Int,
    val language: String? = null,
    val title: String? = null,
    val codec: String? = null,
    val channels: Int? = null,
)

/** A segment the receiver is in that the viewer can skip, and where skipping lands. */
@Serializable
data class CastSkip(val segmentType: String, val endSeconds: Double)

/**
 * What a cast device can decode, guessed from its name — for a receiver too old to say.
 *
 * The receiver probes itself and answers a `CAPABILITIES_REQUEST`; this is only the
 * fallback, and deliberately the same one the web sender falls back to.
 */
fun castCapabilities(deviceName: String): ClientCapabilities {
    val name = deviceName.lowercase()
    val googleTv = listOf("google tv", "ccgtv", "shield", "onn", "bravia").any { it in name }
    val ultra = "ultra" in name
    return ClientCapabilities(
        videoCodecs = buildList {
            add("h264")
            add("vp9")
            if (googleTv || ultra) add("hevc")
        },
        // A transcode reaches the receiver as fMP4 through MSE, which rejects MP3, FLAC
        // and Opus inside it; anything else is cheaper re-encoded to AAC than unplayable.
        audioCodecs = buildList {
            add("aac")
            if (googleTv) {
                add("ac3")
                add("eac3")
            }
        },
        containers = listOf("mp4"),
    )
}

/** What the receiver was handed: a playlist to follow, or a file to play. */
fun castMethod(sessionMethod: String?): String =
    if (sessionMethod == "direct_play") "direct_play" else "hls"

fun castContentType(sessionMethod: String?): String =
    if (castMethod(sessionMethod) == "direct_play") "video/mp4" else "application/x-mpegURL"

/** A book handed to the receiver, which plays its files in turn and saves the place itself. */
@Serializable
data class CastInitAudiobook(
    val baseUrl: String,
    val token: String,
    val bookId: String,
    val fileIds: List<String>,
    val fileDurations: List<Double>,
    val currentFileIndex: Int,
    val currentTime: Double,
    val duration: Double,
    val chapters: List<CastAudiobookChapter>,
    val metadata: CastAudiobookMetadata,
    val playbackRate: Float,
)

/** Times are within the chapter's file: the receiver plays one file at a time. */
@Serializable
data class CastAudiobookChapter(
    val index: Int,
    val fileIndex: Int,
    val title: String?,
    val startSeconds: Double,
    val endSeconds: Double,
)

@Serializable
data class CastAudiobookMetadata(val title: String, val author: String? = null, val coverUrl: String? = null)

/** A read-aloud handed to the receiver: one live stream the server renders as it goes. */
@Serializable
data class CastInitTts(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val coverUrl: String? = null,
    val startMs: Long = 0,
)
