package dev.kobbler.app.ui.player.audiobook

import dev.kobbler.app.data.AudioFileDto

enum class SleepMode { Off, Min15, Min30, Min45, Min60, EndOfChapter }

fun pickBestAudioSource(allFiles: List<AudioFileDto>): List<AudioFileDto> {
    if (allFiles.isEmpty()) return allFiles
    val byFormat = allFiles.filter { it.available }.groupBy { it.format.uppercase() }
    if (byFormat.size <= 1) return allFiles.filter { it.available }

    val formatPriority = listOf("M4B", "M4A", "OPUS", "OGG", "MP3", "FLAC")
    val bestFormat = byFormat.keys
        .sortedWith(compareBy<String> {
            val idx = formatPriority.indexOf(it)
            if (idx >= 0) idx else formatPriority.size
        }.thenBy { byFormat[it]?.size ?: Int.MAX_VALUE })
        .first()

    return byFormat[bestFormat] ?: allFiles.filter { it.available }
}
