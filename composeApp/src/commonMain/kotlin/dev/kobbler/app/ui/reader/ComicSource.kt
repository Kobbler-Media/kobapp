package dev.kobbler.app.ui.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.ZipArchive
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import dev.kobbler.app.platform.openZipArchive

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

/** An open CBZ plus its page order. The archive is kept compressed and pages are inflated
 *  on demand — a 200-page comic decodes to hundreds of MB if it is all held at once. */
internal class ComicSource(private val zip: ZipArchive, private val pageNames: List<String>) {
    val pageCount: Int get() = pageNames.size

    suspend fun readPage(index: Int): ByteArray? {
        val name = pageNames.getOrNull(index) ?: return null
        return withContext(Dispatchers.Default) { zip.readBytes(name) }
    }

    fun close() = zip.close()
}

internal sealed interface ComicLoadResult {
    data class Ready(val source: ComicSource) : ComicLoadResult
    data class Failed(val message: String) : ComicLoadResult
}

internal suspend fun loadComic(book: Book, api: KobblerApi): ComicLoadResult {
    val payload = fetchBookFile(api, book.id, "comic_${book.id}", listOf("CBZ"))
    return withContext(Dispatchers.Default) {
        payload.bytes?.let { if (isRar(it)) return@withContext ComicLoadResult.Failed(NOT_A_ZIP) }
        // Disk-backed when the platform has a filesystem, so a large CBZ is never resident.
        // A RAR fails to open here too, which is the overwhelmingly likely cause.
        val zip = payload.path?.let { openZipArchive(it) }
            ?: payload.bytes?.let { ZipArchive(it) }
            ?: return@withContext ComicLoadResult.Failed(NOT_A_ZIP)
        val pages = zip.entries()
            .filter { it.substringAfterLast(".").lowercase() in IMAGE_EXTENSIONS }
            .sorted()
        if (pages.isEmpty()) {
            zip.close()
            ComicLoadResult.Failed("No images found in archive")
        } else {
            ComicLoadResult.Ready(ComicSource(zip, pages))
        }
    }
}

private const val NOT_A_ZIP =
    "CBR (RAR) format is not supported. Please convert to CBZ (ZIP) format."

private fun isRar(data: ByteArray) = data.size >= 4 &&
    data[0] == 0x52.toByte() && data[1] == 0x61.toByte() &&
    data[2] == 0x72.toByte() && data[3] == 0x21.toByte()

/** Bounded LRU of inflated pages, observable so a page renders as soon as it arrives. */
@Stable
internal class ComicPageCache(private val source: ComicSource, private val capacity: Int) {
    private val pages = mutableStateMapOf<Int, ByteArray>()
    private val recent = ArrayDeque<Int>()

    operator fun get(index: Int): ByteArray? = pages[index]

    /** Loads [indices] nearest-first so the page being looked at wins the race. */
    suspend fun load(indices: List<Int>, center: Int = indices.firstOrNull() ?: 0) {
        for (index in indices.sortedBy { abs(it - center) }) {
            if (index !in 0 until source.pageCount) continue
            if (pages.containsKey(index)) {
                touch(index)
                continue
            }
            val bytes = source.readPage(index) ?: continue
            pages[index] = bytes
            touch(index)
            while (recent.size > capacity) pages.remove(recent.removeAt(0))
        }
    }

    private fun touch(page: Int) {
        recent.remove(page)
        recent.addLast(page)
    }
}
