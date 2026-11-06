package dev.kobbler.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.reader.pack.Manifest
import dev.kobbler.app.ui.reader.pack.PackChapter
import dev.kobbler.app.ui.reader.pack.PackJson
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** A book's reader pack: the manifest up front, chapters fetched as they are reached. */
@Immutable
@OptIn(ExperimentalAtomicApi::class)
internal class ReaderBook(val bookId: String, val manifest: Manifest, private val api: KobblerApi) {
    private val lock = Mutex()
    private val cache = LinkedHashMap<Int, FetchedChapter>()
    private val views = AtomicReference<Map<Int, ChapterView>>(emptyMap())

    val chapterCount: Int get() = manifest.chapters.size
    val lastChapter: Int get() = manifest.chapters.lastIndex
    val weights: List<Int> = manifest.chapters.map { it.weight.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }

    private val stylesKey: Int by lazy {
        PackJson.encodeToString(ListSerializer(PackTextStyle.serializer()), manifest.styles).hashCode()
    }

    fun title(chapter: Int): String? = manifest.chapters.getOrNull(chapter)?.title

    fun assetUrl(src: String): String = api.readerPackAssetUrl(bookId, src)

    suspend fun assetBytes(src: String): ByteArray? = api.readerPackAssetBytes(bookId, src)

    suspend fun chapter(index: Int): PackChapter = fetched(index).chapter

    suspend fun fetched(index: Int): FetchedChapter {
        lock.withLock { cache[index]?.let { return it } }
        val json = api.readerPackChapterJson(bookId, index)
        val fetched = withContext(Dispatchers.Default) {
            FetchedChapter(PackJson.decodeFromString(PackChapter.serializer(), json), 31 * json.hashCode() + stylesKey)
        }
        lock.withLock {
            cache[index] = fetched
            while (cache.size > CachedChapters) cache.remove(cache.keys.first())
        }
        return fetched
    }

    fun cachedView(index: Int): ChapterView? = views.load()[index]

    fun cacheView(view: ChapterView) {
        while (true) {
            val current = views.load()
            val next = LinkedHashMap(current).apply {
                remove(view.chapterIndex)
                put(view.chapterIndex, view)
                while (size > CachedChapters) remove(keys.first())
            }
            if (views.compareAndSet(current, next)) return
        }
    }

    private companion object {
        const val CachedChapters = 8
    }
}

/** [contentKey] changes whenever the chapter or the book's styles do. */
internal class FetchedChapter(val chapter: PackChapter, val contentKey: Int)

@Immutable
internal class ChapterView(
    val chapterIndex: Int,
    val chapter: PackChapter,
    val contentKey: Int,
    val page: FittedPage?,
) {
    val blocks get() = chapter.blocks

    // Only read aloud needs it, and building it costs as much as laying out the first pages.
    val tts: ChapterTtsView by lazy { buildChapterTtsView(chapter.blocks) }
}

internal suspend fun buildChapterView(book: ReaderBook, chapterIndex: Int): ChapterView? {
    if (chapterIndex !in 0..book.lastChapter) return null
    book.cachedView(chapterIndex)?.let { return it }
    val fetched = book.fetched(chapterIndex)
    val chapter = fetched.chapter
    return withContext(Dispatchers.Default) {
        ChapterView(
            chapterIndex = chapterIndex,
            chapter = chapter,
            contentKey = fetched.contentKey,
            page = fittedPageOf(chapter.blocks, fixedLayout = book.manifest.chapters[chapterIndex].fixedLayout != null),
        )
    }.also(book::cacheView)
}

// Views are cached by chapter rather than held per call site: a page turn across a
// chapter moves the neighbour into the current slot, which must find it built.
@Composable
internal fun rememberChapterView(book: ReaderBook, chapterIndex: Int): ChapterView? {
    var view by remember(book) { mutableStateOf<ChapterView?>(null) }
    LaunchedEffect(book, chapterIndex) {
        if (view?.chapterIndex != chapterIndex) view = orNull { buildChapterView(book, chapterIndex) }
    }
    return view?.takeIf { it.chapterIndex == chapterIndex } ?: book.cachedView(chapterIndex)
}

/** Null when [block] fails for any reason but cancellation, which must keep propagating. */
internal suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
