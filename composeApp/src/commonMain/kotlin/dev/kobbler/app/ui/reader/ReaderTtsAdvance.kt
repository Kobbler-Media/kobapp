package dev.kobbler.app.ui.reader

internal data class SpokenChapter(val index: Int, val text: String)

/**
 * The next chapter from [from] that has something to read aloud, or null at the end of
 * the book.
 *
 * A chapter with no speakable text is a plate, a divider or a title page — not the end
 * of the read. Advancing used to be one step and a blank check, so a book with a
 * frontispiece in it stopped reading there, silently, and a listener who had put the
 * phone down got nothing more until they picked it up and pressed play.
 *
 * [onSkip] is called for each one walked past, in order, so the reader can follow.
 */
internal suspend fun nextSpokenChapter(
    from: Int,
    lastIndex: Int,
    textAt: suspend (Int) -> String,
    onSkip: suspend (Int) -> Unit,
): SpokenChapter? {
    var index = from
    while (index <= lastIndex) {
        val text = textAt(index)
        if (text.isNotBlank()) return SpokenChapter(index, text)
        onSkip(index)
        index++
    }
    return null
}
