package dev.kobbler.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.reader.layout.ChapterLayout
import dev.kobbler.app.ui.reader.layout.ReaderTypography
import dev.kobbler.app.ui.reader.layout.layoutChapter
import dev.kobbler.app.ui.reader.pack.Manifest
import dev.kobbler.app.ui.reader.pack.PackChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A book's reader pack: the manifest up front, chapters fetched as they are reached. */
@Immutable
internal class ReaderBook(val bookId: String, val manifest: Manifest, private val api: KobblerApi) {
    private val lock = Mutex()
    private val cache = LinkedHashMap<Int, PackChapter>()

    val chapterCount: Int get() = manifest.chapters.size
    val lastChapter: Int get() = manifest.chapters.lastIndex
    val weights: List<Int> = manifest.chapters.map { it.weight.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }

    fun title(chapter: Int): String? = manifest.chapters.getOrNull(chapter)?.title

    fun assetUrl(src: String): String = api.readerPackAssetUrl(bookId, src)

    suspend fun assetBytes(src: String): ByteArray? = api.readerPackAssetBytes(bookId, src)

    suspend fun chapter(index: Int): PackChapter {
        lock.withLock { cache[index]?.let { return it } }
        val fetched = api.readerPackChapter(bookId, index)
        lock.withLock {
            cache[index] = fetched
            while (cache.size > CachedChapters) cache.remove(cache.keys.first())
        }
        return fetched
    }

    private companion object {
        const val CachedChapters = 8
    }
}

@Immutable
internal class ChapterView(
    val chapterIndex: Int,
    val chapter: PackChapter,
    val tts: ChapterTtsView,
    val page: FittedPage?,
) {
    val blocks get() = chapter.blocks
}

internal suspend fun buildChapterView(book: ReaderBook, chapterIndex: Int): ChapterView? {
    if (chapterIndex !in 0..book.lastChapter) return null
    val chapter = book.chapter(chapterIndex)
    return withContext(Dispatchers.Default) {
        ChapterView(
            chapterIndex = chapterIndex,
            chapter = chapter,
            tts = buildChapterTtsView(chapter.blocks),
            page = fittedPageOf(chapter.blocks, fixedLayout = book.manifest.chapters[chapterIndex].fixedLayout != null),
        )
    }
}

@Composable
internal fun rememberChapterView(book: ReaderBook, chapterIndex: Int): ChapterView? {
    var view by remember(book) { mutableStateOf<ChapterView?>(null) }
    LaunchedEffect(book, chapterIndex) {
        if (view?.chapterIndex != chapterIndex) view = orNull { buildChapterView(book, chapterIndex) }
    }
    return view?.takeIf { it.chapterIndex == chapterIndex }
}

/** The chapter paginated for [pageSize], recomputed off the main thread whenever the
 *  typography or the page changes. Null until the first layout lands. */
@Composable
internal fun rememberChapterLayout(view: ChapterView?, typography: ReaderTypography, pageSize: IntSize, manifest: Manifest): ChapterLayout? {
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    var layout by remember { mutableStateOf<Pair<Any, ChapterLayout>?>(null) }
    val key = listOf(view, typography, pageSize, density)
    LaunchedEffect(key) {
        if (view == null || pageSize.width <= 0 || pageSize.height <= 0) return@LaunchedEffect
        val built = layoutChapter(view.blocks, manifest.styles, typography, pageSize, density, resolver, direction)
        layout = key to built
    }
    return layout?.takeIf { it.first == key }?.second
}

/** Null when [block] fails for any reason but cancellation, which must keep propagating. */
internal suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
