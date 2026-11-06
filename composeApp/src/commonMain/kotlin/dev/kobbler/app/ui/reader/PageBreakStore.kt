package dev.kobbler.app.ui.reader

import dev.kobbler.app.platform.FileCache
import dev.kobbler.app.platform.ioDispatcher
import dev.kobbler.app.ui.reader.layout.PageBreaks
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface LayoutFiles {
    fun read(key: String): ByteArray?
    fun write(key: String, bytes: ByteArray)
    fun remove(key: String)
}

internal object CacheLayoutFiles : LayoutFiles {
    override fun read(key: String): ByteArray? = FileCache.get(key)
    override fun write(key: String, bytes: ByteArray) = FileCache.put(key, bytes)
    override fun remove(key: String) = FileCache.remove(key)
}

/**
 * A book's page breaks on disk, one file per book. Only the last [Fingerprints] page
 * setups are kept per book, and only the last [Books] books opened keep a file at all.
 */
internal class PageBreakStore(private val bookId: String, private val files: LayoutFiles = CacheLayoutFiles) {
    private val lock = Mutex()
    private var entries: List<SavedBreaks>? = null

    suspend fun get(fingerprint: String, chapter: Int, contentKey: Int): PageBreaks? = lock.withLock {
        loaded().firstOrNull { it.fingerprint == fingerprint && it.chapter == chapter }
            ?.takeIf { it.contentKey == contentKey }?.breaks
    }

    suspend fun put(fingerprint: String, chapter: Int, contentKey: Int, breaks: PageBreaks) = lock.withLock {
        val kept = loaded().filterNot { it.fingerprint == fingerprint && it.chapter == chapter }
        val setups = (listOf(fingerprint) + kept.map { it.fingerprint }).distinct().take(Fingerprints)
        val next = listOf(SavedBreaks(fingerprint, chapter, contentKey, breaks)) + kept.filter { it.fingerprint in setups }
        entries = next
        withContext(ioDispatcher) {
            files.write(fileKey(bookId), PageBreakCodec.encode(next))
            touch()
        }
    }

    private suspend fun loaded(): List<SavedBreaks> = entries ?: withContext(ioDispatcher) {
        files.read(fileKey(bookId))?.let(PageBreakCodec::decode).orEmpty()
    }.also { entries = it }

    private fun touch() {
        val recent = files.read(IndexKey)?.decodeToString()?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val next = (listOf(bookId) + recent).distinct()
        next.drop(Books).forEach { files.remove(fileKey(it)) }
        files.write(IndexKey, next.take(Books).joinToString("\n").encodeToByteArray())
    }

    companion object {
        const val Fingerprints = 2
        const val Books = 24
        private const val IndexKey = "page-breaks-index"
        fun fileKey(bookId: String) = "page-breaks-$bookId"
    }
}
