package dev.kobbler.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.kobbler.app.ui.reader.epub.buildChapterTtsView
import dev.kobbler.app.ui.reader.epub.parseXhtmlToBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private const val SNIPPET_CONTEXT = 40

@Immutable
internal data class SearchHit(val chapterIndex: Int, val chapterTitle: String, val snippet: String)

/** Whole-book plain text, folded to lower case once so a keystroke costs one `indexOf`
 *  per chapter instead of re-parsing every chapter's XHTML. */
@Immutable
internal class EpubSearchIndex private constructor(private val chapters: List<Entry>) {
    private class Entry(val index: Int, val title: String, val text: String, val folded: String)

    suspend fun search(query: String): List<SearchHit> {
        val needle = fold(query)
        if (needle.length < MIN_QUERY_LENGTH) return emptyList()
        return chapters.mapNotNull { chapter ->
            coroutineContext.ensureActive()
            val at = chapter.folded.indexOf(needle)
            if (at < 0) null
            else SearchHit(chapter.index, chapter.title, snippet(chapter.text, at, needle.length))
        }
    }

    companion object {
        const val MIN_QUERY_LENGTH = 2

        suspend fun build(epub: EpubBook): EpubSearchIndex = withContext(Dispatchers.Default) {
            EpubSearchIndex(
                epub.chapters.mapIndexed { idx, chapter ->
                    coroutineContext.ensureActive()
                    val text = buildChapterTtsView(parseXhtmlToBlocks(chapter.xhtml, includeHidden = true)).plainText
                    Entry(idx, chapter.title ?: "Chapter ${idx + 1}", text, fold(text))
                }
            )
        }

        /** Per-character folding: `String.lowercase()` can change length (e.g. `İ`), which
         *  would slide every snippet offset in the chapter. */
        private fun fold(s: String): String = buildString(s.length) {
            for (c in s) append(c.lowercaseChar())
        }

        private fun snippet(text: String, at: Int, matchLength: Int): String {
            val start = (at - SNIPPET_CONTEXT).coerceAtLeast(0)
            val end = (at + matchLength + SNIPPET_CONTEXT).coerceAtMost(text.length)
            return (if (start > 0) "..." else "") +
                text.substring(start, end) +
                (if (end < text.length) "..." else "")
        }
    }
}

/** Builds the index the first time [active] goes true, then keeps it for the book's lifetime. */
@Composable
internal fun rememberEpubSearchIndex(epub: EpubBook, active: Boolean): EpubSearchIndex? {
    var index by remember(epub) { mutableStateOf<EpubSearchIndex?>(null) }
    LaunchedEffect(epub, active) {
        if (active && index == null) index = EpubSearchIndex.build(epub)
    }
    return index
}
