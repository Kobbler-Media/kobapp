package dev.kobbler.app.ui.player

import dev.kobbler.app.data.AudioTrackDto
import dev.kobbler.app.data.PlaybackSubtitleTrackDto
import dev.kobbler.kobplayer.TrackInfo

internal fun List<AudioTrackDto>.audioTrackInfos(): List<TrackInfo> = mapIndexed { i, track ->
    val label = buildString {
        append(track.title?.takeIf { it.isNotBlank() } ?: spokenName(track.languageName, track.language) ?: "Track ${i + 1}")
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

/**
 * The track shown without being asked for: a forced one in a language the viewer reads,
 * otherwise their first language under audio in another. A track the release merely
 * flags default is not it. Mirrors the server's `decide_subtitles`.
 */
internal fun List<TrackInfo>.unaskedBy(
    server: List<PlaybackSubtitleTrackDto>,
    foreignAudio: Boolean,
    languages: List<String>,
): TrackInfo? {
    val ordered = alignedWith(server) ?: return null
    // Releases ship a forced track per dubbed language; one the viewer can't read translates nothing.
    val forced = ordered.indexOfFirst { track ->
        track.isForced && (isUntagged(track.language) || languages.isEmpty() || languages.any { sameLanguage(track.language, it) })
    }
    val wanted = if (forced < 0 && foreignAudio) ordered.indexOfFirst { sameLanguage(it.language, languages.firstOrNull()) } else forced
    return getOrNull(wanted)
}

private fun List<TrackInfo>.alignedWith(server: List<PlaybackSubtitleTrackDto>) =
    server.sortedBy { it.streamIndex }.takeIf { it.size == size }

// What muxers write when they have nothing to say: "Track 3", "Subtitle 2", "SubRip".
private val GenericTitle = Regex("""(?i)\s*((track|subtitles?|subs?|stream|text)\s*#?\s*[\d:]*|subrip|srt|ass|ssa|pgs|vobsub|webvtt|mov_text)\s*""")

private fun PlaybackSubtitleTrackDto.label(position: Int) = buildString {
    val lang = language?.takeIf { it.isNotBlank() }
    val langName = spokenName(languageName, lang)
    val named = title?.takeIf { it.isNotBlank() && !(GenericTitle.matches(it) && lang != null) }
    // "European" and "Latin American" are two Spanish tracks, not two languages.
    val namesLanguage = named != null && named.split(Regex("[^\\p{L}]+")).any { sameLanguage(it, lang) }
    when {
        named != null && langName != null && !namesLanguage -> append("$langName · $named")
        else -> append(named ?: langName ?: title?.takeIf { it.isNotBlank() } ?: "Track ${position + 1}")
    }
    if (isForced) append(" · forced")
    if (isHearingImpaired && named?.contains("SDH", ignoreCase = true) != true) append(" · SDH")
    if (isDefault) append(" · default")
}

// The server names the languages it recognises; any other code is shown as the code.
private fun spokenName(name: String?, code: String?): String? =
    name?.takeIf { it.isNotBlank() } ?: code?.takeIf { it.isNotBlank() }?.uppercase()
