package dev.kobbler.app.castreceiver

import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.CastAudioTrack
import dev.kobbler.app.data.CastTextTrack
import dev.kobbler.app.data.CastTracks
import dev.kobbler.app.data.PlaybackSubtitleTrackDto

/**
 * The tracks as the phone sees them. Subtitles are numbered from 1 in the order the
 * session lists the renderable ones, which is how the web receiver numbers them too.
 */
fun castTracks(
    subtitles: List<PlaybackSubtitleTrackDto>,
    selectedSubtitleTrackId: String?,
    audio: List<AudioTrackDto>,
    selectedAudioIndex: Int?,
) = CastTracks(
    subtitles = subtitles.mapIndexed { i, s ->
        CastTextTrack(
            id = i + 1,
            language = s.language,
            languageName = s.languageName,
            title = s.title,
            forced = s.isForced,
            hearingImpaired = s.isHearingImpaired,
        )
    },
    activeSubtitleId = selectedSubtitleTrackId?.let { id -> subtitles.indexOfFirst { it.trackId == id }.takeIf { it >= 0 }?.plus(1) },
    audioTracks = audio.map { CastAudioTrack(it.streamIndex, it.language, it.languageName, it.title, it.codec, it.channels) },
    activeAudioIndex = selectedAudioIndex,
)

fun subtitleTrackIdFor(castId: Int?, subtitles: List<PlaybackSubtitleTrackDto>): String? =
    castId?.let { subtitles.getOrNull(it - 1)?.trackId }
