package dev.kobbler.app.ui.reader

/**
 * Start TTS at [offset] in [blockIndex], returning the chapter-global word index the synth
 * began at (the caller must add it to the controller's per-synth index before looking up
 * a highlight), or null when nothing spoken follows.
 *
 * Matches the first word at or *after* the point: images, rules and code blocks emit no
 * words, so an exact match would make pointing at a figure a silent no-op. Within a block
 * the word under [offset] is included, so a press mid-word reads that word.
 */
internal fun startReadingAtBlock(
    view: ChapterTtsView,
    blockIndex: Int,
    offset: Int,
    startSynth: (String) -> Unit,
): Int? {
    val word = view.words.firstOrNull {
        it.blockIndex > blockIndex ||
            (it.blockIndex == blockIndex && (it.highlightCharRange?.last ?: Int.MAX_VALUE) >= offset)
    } ?: return null
    val start = word.chapterCharOffset.coerceIn(0, view.plainText.length)
    val remaining = view.plainText.substring(start)
    if (remaining.isBlank()) return null
    startSynth(remaining)
    return word.globalIndex
}
