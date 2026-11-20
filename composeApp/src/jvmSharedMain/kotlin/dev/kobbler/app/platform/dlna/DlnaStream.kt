package dev.kobbler.app.platform.dlna

import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.CastInitSession
import dev.kobbler.app.data.PlaybackSubtitleTrackDto
import dev.kobbler.app.data.SessionResponse
import dev.kobbler.app.data.SkipSegment

/** One server session as the renderer is playing it. */
internal data class DlnaStream(
    val sessionId: String,
    val mediaId: String?,
    val mediaType: String,
    val progressiveUrl: String?,
    val directUrl: String?,
    val playlistUrl: String?,
    /** Where in the title the stream's zero is: a transcode starts where it was opened. */
    val offsetSec: Double,
    val durationSec: Double,
    val subtitles: List<PlaybackSubtitleTrackDto>,
    val audioTracks: List<AudioTrackDto>,
    val skipSegments: List<SkipSegment>,
) {
    val url: String? get() = progressiveUrl ?: directUrl ?: playlistUrl

    /** A direct file is seekable in place; any stream the server produces is reopened instead. */
    val seekable: Boolean get() = progressiveUrl == null && directUrl != null

    val mime: String get() = when {
        progressiveUrl != null -> "video/mp2t"
        directUrl != null -> "video/mp4"
        else -> "application/vnd.apple.mpegurl"
    }

    /** Mirrors the `contentFeatures.dlna.org` the server sends with stream.ts; some renderers compare them. */
    val protocolInfo: String get() =
        if (progressiveUrl != null) "http-get:*:video/mp2t:DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        else "http-get:*:$mime:*"

    val textTracks: List<PlaybackSubtitleTrackDto> get() = subtitles.filter { it.srtUrl != null }
}

internal fun CastInitSession.toStream(api: ReceiverApi) = DlnaStream(
    sessionId = sessionId,
    mediaId = mediaId,
    mediaType = mediaType,
    progressiveUrl = api.resolve(progressiveUrl),
    directUrl = api.resolve(directUrl).takeIf { progressiveUrl == null },
    playlistUrl = api.resolve(playlistUrl),
    offsetSec = if (progressiveUrl == null && directUrl != null) 0.0 else startOffset,
    durationSec = duration,
    subtitles = subtitles,
    audioTracks = audioTracks,
    skipSegments = skipSegments,
)

internal fun SessionResponse.toStream(api: ReceiverApi, mediaId: String, mediaType: String) = DlnaStream(
    sessionId = sessionId,
    mediaId = mediaId,
    mediaType = mediaType,
    progressiveUrl = api.resolve(progressiveUrl),
    directUrl = api.resolve(directUrl),
    playlistUrl = api.resolve(playlistUrl),
    offsetSec = if (directUrl != null) 0.0 else startTime ?: 0.0,
    durationSec = durationSeconds,
    subtitles = subtitles,
    audioTracks = audioTracks,
    skipSegments = skipSegments,
)
