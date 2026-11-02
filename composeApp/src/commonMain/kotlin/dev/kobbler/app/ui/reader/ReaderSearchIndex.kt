package dev.kobbler.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.kobbler.app.ui.reader.pack.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private const val SNIPPET_CONTEXT = 40
private const val CONCURRENT_FETCHES = 4

@Immutable
internal data class SearchHit(val position: Position, val chapterTitle: String, val snippet: String)

/** Whole-book plain text, folded to lower case once so a keystroke costs one `indexOf`
 *  per chapter. */
@Immutable
internal class BookSearchIndex private constructor(private val chapters: List<Entry>) {
    private class Entry(val index: Int, val title: String, val tts: ChapterTtsView, val folded: String)

    suspend fun search(query: String): List<SearchHit> {
        val needle = fold(query)
        if (needle.length < MIN_QUERY_LENGTH) return emptyList()
        return chapters.mapNotNull { chapter ->
            coroutineContext.ensureActive()
            val at = chapter.folded.indexOf(needle)
            if (at < 0) return@mapNotNull null
            val word = chapter.tts.words.lastOrNull { it.chapterCharOffset <= at }
            val offset = word?.highlightCharRange?.first ?: 0
            SearchHit(
                Position(chapter.index, word?.blockIndex ?: 0, offset),
                chapter.title,
                snippet(chapter.tts.plainText, at, needle.length),
            )
        }
    }

    companion object {
        const val MIN_QUERY_LENGTH = 2

        suspend fun build(book: ReaderBook): BookSearchIndex = coroutineScope {
            val permits = Semaphore(CONCURRENT_FETCHES)
            val entries = book.manifest.chapters.indices.map { idx ->
                async {
                    val chapter = permits.withPermit { book.chapter(idx) }
                    withContext(Dispatchers.Default) {
                        val tts = buildChapterTtsView(chapter.blocks)
                        Entry(idx, book.title(idx) ?: "Chapter ${idx + 1}", tts, fold(tts.plainText))
                    }
                }
            }.awaitAll()
            BookSearchIndex(entries)
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
internal fun rememberBookSearchIndex(book: ReaderBook, active: Boolean): BookSearchIndex? {
    var index by remember(book) { mutableStateOf<BookSearchIndex?>(null) }
    LaunchedEffect(book, active) {
        if (active && index == null) index = orNull { BookSearchIndex.build(book) }
    }
    return index
}
