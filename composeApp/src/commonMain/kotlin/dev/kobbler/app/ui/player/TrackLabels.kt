package dev.kobbler.app.ui.player

import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.PlaybackSubtitleTrackDto
import dev.kobbler.kobplayer.TrackInfo

internal fun List<AudioTrackDto>.audioTrackInfos(): List<TrackInfo> = mapIndexed { i, track ->
    val label = buildString {
        append(track.title?.takeIf { it.isNotBlank() } ?: track.language?.takeIf { it.isNotBlank() } ?: "Track ${i + 1}")
        if (track.codec.isNotBlank()) append(" (${track.codec})")
        when (track.channels) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null }?.let { append(" $it") }
    }
    TrackInfo(id = track.streamIndex.toString(), label = label, language = track.language)
}

internal fun List<PlaybackSubtitleTrackDto>.subtitleTrackInfos(): List<TrackInfo> = mapIndexed { i, track ->
    TrackInfo(id = track.trackId ?: "", label = track.label(i), language = track.language)
}

/**
 * The player's own subtitle tracks under the names the server read for them. The player
 * lists a container's tracks in stream order, so they line up by position — and only
 * when the counts agree is that worth trusting.
 */
internal fun List<TrackInfo>.labelledBy(server: List<PlaybackSubtitleTrackDto>): List<TrackInfo> {
    val ordered = alignedWith(server) ?: return this
    return zip(ordered).mapIndexed { i, (own, dto) -> own.copy(label = dto.label(i), language = dto.language ?: own.language) }
}

internal fun List<TrackInfo>.forcedBy(server: List<PlaybackSubtitleTrackDto>): TrackInfo? {
    val ordered = alignedWith(server) ?: return null
    return getOrNull(ordered.indexOfFirst { it.isForced })
}

private fun List<TrackInfo>.alignedWith(server: List<PlaybackSubtitleTrackDto>) =
    server.sortedBy { it.streamIndex }.takeIf { it.size == size }

// What muxers write when they have nothing to say: "Track 3", "Subtitle 2", "SubRip".
private val GenericTitle = Regex("""(?i)\s*((track|subtitles?|subs?|stream|text)\s*#?\s*[\d:]*|subrip|srt|ass|ssa|pgs|vobsub|webvtt|mov_text)\s*""")

private fun PlaybackSubtitleTrackDto.label(position: Int) = buildString {
    val lang = language?.takeIf { it.isNotBlank() }
    val named = title?.takeIf { it.isNotBlank() && !(GenericTitle.matches(it) && lang != null) }
    // "European" and "Latin American" are two Spanish tracks, not two languages.
    val namesLanguage = named != null && named.split(Regex("[^\\p{L}]+")).any { sameLanguage(it, lang) }
    when {
        named != null && lang != null && !namesLanguage -> append("${lang.uppercase()} · $named")
        else -> append(named ?: lang?.uppercase() ?: title?.takeIf { it.isNotBlank() } ?: "Track ${position + 1}")
    }
    if (isForced) append(" · forced")
    if (isHearingImpaired && named?.contains("SDH", ignoreCase = true) != true) append(" · SDH")
    if (isDefault) append(" · default")
}
