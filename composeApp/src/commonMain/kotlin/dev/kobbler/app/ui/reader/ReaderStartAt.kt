package dev.kobbler.app.ui.reader

import dev.kobbler.app.ui.reader.epub.ChapterTtsView

/**
 * Start TTS at [blockIndex], returning the chapter-global word index the synth began at
 * (the caller must add it to the controller's per-synth index before looking up a
 * highlight), or null when nothing spoken follows.
 *
 * Matches the first word at or *after* [blockIndex]: images, dividers, spacers and code
 * blocks emit no words, so an exact match would make pointing at a figure a silent no-op.
 */
internal fun startReadingAtBlock(
    view: ChapterTtsView,
    blockIndex: Int,
    startSynth: (String) -> Unit,
): Int? {
    val word = view.words.firstOrNull { it.blockIndex >= blockIndex } ?: return null
    val offset = word.chapterCharOffset.coerceIn(0, view.plainText.length)
    val remaining = view.plainText.substring(offset)
    if (remaining.isBlank()) return null
    startSynth(remaining)
    return word.globalIndex
}

/** Lowest-indexed block whose measured bounds contain [contentY], or null in the
 *  whitespace the paginated layout leaves at the foot of a page. Lowest rather than
 *  first-found because the bounds come from a snapshot state map with no order contract. */
internal fun blockAt(bounds: Map<Int, IntRange>, contentY: Int): Int? =
    bounds.entries.asSequence()
        .filter { contentY in it.value }
        .minByOrNull { it.key }
        ?.key
