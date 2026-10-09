package dev.kobbler.app.ui.player

import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.PlaybackSubtitleTrackDto

/** The server's track decision, in the form VLC accepts before it opens the stream. */
data class TrackIntent(
    val audioLanguage: String? = null,
    val subtitleOrdinal: Int? = null,
    val subtitlesOff: Boolean = false,
)

/**
 * Turns what the server picked into options that can be set at open time.
 *
 * Told afterwards, a track choice costs the whole initial parse to wait for — and the
 * parse finishes long after the tracks are known, because it is not done until every
 * decoder and the video output have been built. Told at open time it costs nothing, and
 * the wrong track is never briefly selected on the way.
 *
 * Subtitles are named by ordinal, audio by language, and the difference is deliberate:
 * `sub-track` and `audio-track` count from zero within their own category, and an ordinal
 * that overshoots selects *nothing at all* rather than falling back. For subtitles that
 * is indistinguishable from the "off" we would otherwise have asked for. For audio it is
 * silence, so audio is named by language, which simply falls through to VLC's own pick
 * when it does not match — and only when the language names exactly one track.
 */
fun trackIntent(
    audioTracks: List<AudioTrackDto>,
    selectedAudioIndex: Int?,
    subtitles: List<PlaybackSubtitleTrackDto>,
    selectedSubtitleIndex: Int?,
): TrackIntent {
    // A sidecar the server renders for us is not the container's subtitle track, and
    // asking VLC for one of its own on top would draw both.
    val hasSidecar = subtitles.any {
        !it.url.isNullOrBlank() && (it.format == "vtt" || it.format == "srt")
    }
    val subtitlesOff = selectedSubtitleIndex == null || hasSidecar

    val embedded = subtitles.filter { (it.streamIndex ?: -1) >= 0 }.sortedBy { it.streamIndex }
    val subtitleOrdinal = if (subtitlesOff) {
        null
    } else {
        embedded.indexOfFirst { it.streamIndex == selectedSubtitleIndex }.takeIf { it >= 0 }
    }

    val chosenLanguage = audioTracks
        .firstOrNull { it.streamIndex == selectedAudioIndex }
        ?.language
        ?.takeIf { it.isNotBlank() }
    val audioLanguage = chosenLanguage?.takeIf { language ->
        audioTracks.size > 1 && audioTracks.count { it.language == language } == 1
    }

    return TrackIntent(audioLanguage, subtitleOrdinal, subtitlesOff)
}
